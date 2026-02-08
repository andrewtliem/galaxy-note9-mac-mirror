import Cocoa
import Foundation
import Network
import ImageIO

struct Config {
    let port: UInt16
    let token: String
    let sensitivity: Double
    let verbose: Bool
    let useWarp: Bool
    let invertY: Bool
    let imagePort: UInt16
    let imageMaxSize: CGFloat
    let controlPort: UInt16
}

final class ClientRegistry {
    private let queue = DispatchQueue(label: "pointerpad.client.registry")
    private var host: String?

    func update(host: String) {
        queue.sync { self.host = host }
    }

    func getHost() -> String? {
        queue.sync { host }
    }
}

final class MouseController {
    private var leftDown = false
    private var rightDown = false
    private let sensitivity: Double
    private let useWarp: Bool
    private let invertY: Bool
    private var currentPosition: CGPoint?

    init(sensitivity: Double, useWarp: Bool, invertY: Bool) {
        self.sensitivity = sensitivity
        self.useWarp = useWarp
        self.invertY = invertY
    }

    func move(dx: Double, dy: Double) {
        let current = currentPosition ?? NSEvent.mouseLocation
        let yDelta = dy * sensitivity * (invertY ? 1.0 : -1.0)
        let target = CGPoint(x: current.x + dx * sensitivity, y: current.y + yDelta)
        let clamped = clampToScreens(point: target)
        currentPosition = clamped

        if useWarp {
            CGWarpMouseCursorPosition(clamped)
            CGAssociateMouseAndMouseCursorPosition(1)
        }

        let type: CGEventType = leftDown ? .leftMouseDragged : (rightDown ? .rightMouseDragged : .mouseMoved)
        let button: CGMouseButton = leftDown ? .left : (rightDown ? .right : .left)
        postMouse(type: type, button: button, position: clamped)
    }

    func setButton(button: String, isDown: Bool) {
        let isLeft = button.lowercased() == "left"
        let pos = clampToScreens(point: currentPosition ?? NSEvent.mouseLocation)
        currentPosition = pos

        if isLeft {
            leftDown = isDown
            postMouse(type: isDown ? .leftMouseDown : .leftMouseUp, button: .left, position: pos)
        } else {
            rightDown = isDown
            postMouse(type: isDown ? .rightMouseDown : .rightMouseUp, button: .right, position: pos)
        }
    }

    func scroll(dx: Double, dy: Double) {
        let scrollEvent = CGEvent(
            scrollWheelEvent2Source: nil,
            units: .line,
            wheelCount: 2,
            wheel1: Int32(-dy),
            wheel2: Int32(dx),
            wheel3: 0
        )
        scrollEvent?.post(tap: .cghidEventTap)
    }

    private func postMouse(type: CGEventType, button: CGMouseButton, position: CGPoint) {
        guard let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: position, mouseButton: button) else {
            return
        }
        event.post(tap: .cghidEventTap)
    }

    private func clampToScreens(point: CGPoint) -> CGPoint {
        let screens = NSScreen.screens.map { $0.frame }
        guard var union = screens.first else { return point }
        for frame in screens.dropFirst() {
            union = union.union(frame)
        }
        let x = min(max(point.x, union.minX), union.maxX)
        let y = min(max(point.y, union.minY), union.maxY)
        return CGPoint(x: x, y: y)
    }
}

final class DrawingView: NSView {
    struct Stroke {
        let id: String
        let color: NSColor
        let width: CGFloat
        var points: [CGPoint]
    }

    private enum ImageDragMode {
        case none
        case move
        case resize
    }

    struct ImageLayer {
        let id: String
        let image: NSImage
        var x: CGFloat
        var y: CGFloat
        var width: CGFloat
        var height: CGFloat
    }

    private var strokes: [Stroke] = []
    private var strokeIndex: [String: Int] = [:]
    private var images: [ImageLayer] = []
    private var imageIndex: [String: Int] = [:]
    private var viewOffset = CGPoint.zero
    private var viewScale: CGFloat = 1.0
    private var sourceSize = CGSize.zero
    private var activeImageIndex: Int?
    private var activeImageMode: ImageDragMode = .none
    private var imageGrabOffset = CGPoint.zero

    private let imageHandleSize: CGFloat = 16
    private let minImageSize: CGFloat = 32

    var onImageMove: ((String, CGFloat, CGFloat) -> Void)?
    var onImageResize: ((String, CGFloat, CGFloat) -> Void)?

    override var isFlipped: Bool { true }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.backgroundColor = NSColor.white.cgColor
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        wantsLayer = true
        layer?.backgroundColor = NSColor.white.cgColor
    }

    func beginStroke(id: String, point: CGPoint, color: NSColor, width: CGFloat) {
        let stroke = Stroke(id: id, color: color, width: width, points: [point])
        strokeIndex[id] = strokes.count
        strokes.append(stroke)
        needsDisplay = true
    }

    func appendPoint(id: String, point: CGPoint) {
        guard let index = strokeIndex[id] else { return }
        strokes[index].points.append(point)
        needsDisplay = true
    }

    func endStroke(id: String) {
        strokeIndex.removeValue(forKey: id)
        needsDisplay = true
    }

    func undo() {
        if let last = strokes.last {
            strokeIndex.removeValue(forKey: last.id)
            strokes.removeLast()
            needsDisplay = true
        }
    }

    func clear() {
        strokes.removeAll()
        strokeIndex.removeAll()
        needsDisplay = true
    }

    func addImage(id: String, image: NSImage, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
        let layer = ImageLayer(id: id, image: image, x: x, y: y, width: width, height: height)
        imageIndex[id] = images.count
        images.append(layer)
        needsDisplay = true
    }

    func moveImage(id: String, x: CGFloat, y: CGFloat) {
        guard let index = imageIndex[id] else { return }
        images[index].x = x
        images[index].y = y
        needsDisplay = true
    }

    func resizeImage(id: String, width: CGFloat, height: CGFloat) {
        guard let index = imageIndex[id] else { return }
        images[index].width = max(1, width)
        images[index].height = max(1, height)
        needsDisplay = true
    }

    func updateView(offset: CGPoint, scale: CGFloat, sourceSize: CGSize) {
        self.viewOffset = offset
        self.viewScale = max(0.01, scale)
        self.sourceSize = sourceSize
        needsDisplay = true
    }

    func erase(at point: CGPoint, radius: CGFloat) {
        guard !strokes.isEmpty else { return }
        let radiusSq = radius * radius
        var kept: [Stroke] = []
        for stroke in strokes {
            let hit = stroke.points.contains { p in
                let dx = p.x - point.x
                let dy = p.y - point.y
                return dx * dx + dy * dy <= radiusSq
            }
            if !hit {
                kept.append(stroke)
            }
        }
        if kept.count != strokes.count {
            strokes = kept
            strokeIndex.removeAll()
            for (idx, stroke) in strokes.enumerated() {
                strokeIndex[stroke.id] = idx
            }
            needsDisplay = true
        }
    }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        NSColor.white.setFill()
        dirtyRect.fill()

        let transform = viewTransform()
        let scale = transform.scale
        let widthScale = scale

        for image in images {
            let topLeft = worldToScreen(CGPoint(x: image.x, y: image.y), transform: transform)
            let w = image.width * viewScale * scale
            let h = image.height * viewScale * scale
            let rect = NSRect(x: topLeft.x, y: topLeft.y, width: w, height: h)
            image.image.draw(in: rect)

            let handleRect = NSRect(
                x: rect.maxX - imageHandleSize,
                y: rect.maxY - imageHandleSize,
                width: imageHandleSize,
                height: imageHandleSize
            )
            NSColor.black.withAlphaComponent(0.6).setFill()
            handleRect.fill()
            NSColor.white.setStroke()
            NSBezierPath(rect: handleRect).stroke()
        }

        for stroke in strokes {
            guard stroke.points.count >= 2 else { continue }
            let path = NSBezierPath()
            let lineWidth = max(1.0, stroke.width * viewScale * widthScale)
            path.lineWidth = lineWidth
            path.lineCapStyle = .round
            path.lineJoinStyle = .round

            let first = worldToScreen(stroke.points[0], transform: transform)
            path.move(to: first)
            for p in stroke.points.dropFirst() {
                path.line(to: worldToScreen(p, transform: transform))
            }
            stroke.color.setStroke()
            path.stroke()
        }
    }

    override func mouseDown(with event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        let transform = viewTransform()
        if let (index, mode) = hitTestImage(at: point, transform: transform) {
            activeImageIndex = index
            activeImageMode = mode
            let world = screenToWorld(point, transform: transform)
            imageGrabOffset = CGPoint(x: world.x - images[index].x, y: world.y - images[index].y)
        } else {
            activeImageIndex = nil
            activeImageMode = .none
        }
    }

    override func mouseDragged(with event: NSEvent) {
        guard let index = activeImageIndex else { return }
        let point = convert(event.locationInWindow, from: nil)
        let transform = viewTransform()
        let world = screenToWorld(point, transform: transform)
        switch activeImageMode {
        case .move:
            images[index].x = world.x - imageGrabOffset.x
            images[index].y = world.y - imageGrabOffset.y
            onImageMove?(images[index].id, images[index].x, images[index].y)
        case .resize:
            let minWorldW = minImageSize / (viewScale * transform.scale)
            let minWorldH = minImageSize / (viewScale * transform.scale)
            images[index].width = max(minWorldW, world.x - images[index].x)
            images[index].height = max(minWorldH, world.y - images[index].y)
            onImageResize?(images[index].id, images[index].width, images[index].height)
        case .none:
            break
        }
        needsDisplay = true
    }

    override func mouseUp(with event: NSEvent) {
        activeImageIndex = nil
        activeImageMode = .none
    }

    private struct ViewTransform {
        let scale: CGFloat
        let padX: CGFloat
        let padY: CGFloat
    }

    private func viewTransform() -> ViewTransform {
        let srcWidth = max(sourceSize.width, 1)
        let srcHeight = max(sourceSize.height, 1)
        let scale = min(bounds.width / srcWidth, bounds.height / srcHeight)
        let padX = (bounds.width - srcWidth * scale) / 2.0
        let padY = (bounds.height - srcHeight * scale) / 2.0
        return ViewTransform(scale: scale, padX: padX, padY: padY)
    }

    private func screenToWorld(_ point: CGPoint, transform: ViewTransform) -> CGPoint {
        let x = (point.x - transform.padX) / (viewScale * transform.scale) + viewOffset.x
        let y = (point.y - transform.padY) / (viewScale * transform.scale) + viewOffset.y
        return CGPoint(x: x, y: y)
    }

    private func imageScreenRect(_ image: ImageLayer, transform: ViewTransform) -> NSRect {
        let topLeft = worldToScreen(CGPoint(x: image.x, y: image.y), transform: transform)
        let w = image.width * viewScale * transform.scale
        let h = image.height * viewScale * transform.scale
        return NSRect(x: topLeft.x, y: topLeft.y, width: w, height: h)
    }

    private func hitTestImage(at point: CGPoint, transform: ViewTransform) -> (Int, ImageDragMode)? {
        guard !images.isEmpty else { return nil }
        for index in stride(from: images.count - 1, through: 0, by: -1) {
            let rect = imageScreenRect(images[index], transform: transform)
            guard rect.contains(point) else { continue }
            let handleRect = NSRect(
                x: rect.maxX - imageHandleSize,
                y: rect.maxY - imageHandleSize,
                width: imageHandleSize,
                height: imageHandleSize
            )
            let mode: ImageDragMode = handleRect.contains(point) ? .resize : .move
            return (index, mode)
        }
        return nil
    }

    private func worldToScreen(_ point: CGPoint, transform: ViewTransform) -> CGPoint {
        let x = (point.x - viewOffset.x) * viewScale * transform.scale + transform.padX
        let y = (point.y - viewOffset.y) * viewScale * transform.scale + transform.padY
        return CGPoint(x: x, y: y)
    }
}

final class ImageSender {
    private let port: UInt16
    private let maxSize: CGFloat

    init(port: UInt16, maxSize: CGFloat) {
        self.port = port
        self.maxSize = maxSize
    }

    struct PreparedImage {
        let id: String
        let image: NSImage
        let width: CGFloat
        let height: CGFloat
        let jpeg: Data
    }

    func prepare(image: NSImage) -> PreparedImage? {
        guard let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff) else { return nil }
        let resizedImage = resizeImage(from: bitmap)
        guard let jpegData = resizedImage.tiffRepresentation.flatMap({ NSBitmapImageRep(data: $0) })?.representation(using: .jpeg, properties: [.compressionFactor: 0.8]) else {
            return nil
        }
        let id = UUID().uuidString
        return PreparedImage(id: id, image: resizedImage, width: resizedImage.size.width, height: resizedImage.size.height, jpeg: jpegData)
    }

    func send(prepared: PreparedImage, to host: String, token: String) {
        let header = makeHeader(id: prepared.id, width: prepared.width, height: prepared.height, token: token)
        sendPacket(header: header, image: prepared.jpeg, to: host)
    }

    private func resizeImage(from bitmap: NSBitmapImageRep) -> NSImage {
        let width = CGFloat(bitmap.pixelsWide)
        let height = CGFloat(bitmap.pixelsHigh)
        let maxDim = max(width, height)
        let original = NSImage(size: NSSize(width: width, height: height))
        original.addRepresentation(bitmap)
        if maxDim <= maxSize {
            return original
        }
        let scale = maxSize / maxDim
        let newSize = NSSize(width: width * scale, height: height * scale)
        let image = NSImage(size: newSize)
        image.lockFocus()
        NSGraphicsContext.current?.imageInterpolation = .high
        original.draw(in: NSRect(origin: .zero, size: newSize), from: NSRect(origin: .zero, size: original.size), operation: .copy, fraction: 1.0)
        image.unlockFocus()
        return image
    }

    private func makeHeader(id: String, width: CGFloat, height: CGFloat, token: String) -> Data {
        let header: [String: Any] = [
            "id": id,
            "x": 0,
            "y": 0,
            "width": width,
            "height": height,
            "token": token
        ]
        return (try? JSONSerialization.data(withJSONObject: header, options: [])) ?? Data()
    }

    private func sendPacket(header: Data, image: Data, to host: String) {
        guard let port = NWEndpoint.Port(rawValue: port) else { return }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
        let queue = DispatchQueue(label: "pointerpad.image.send")

        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                var payload = Data()
                var headerLen = UInt32(header.count).bigEndian
                var imageLen = UInt32(image.count).bigEndian
                payload.append(Data(bytes: &headerLen, count: 4))
                payload.append(header)
                payload.append(Data(bytes: &imageLen, count: 4))
                payload.append(image)
                connection.send(content: payload, completion: .contentProcessed { _ in
                    connection.cancel()
                })
            case .failed, .cancelled:
                connection.cancel()
            default:
                break
            }
        }

        connection.start(queue: queue)
    }
}

final class ControlSender {
    private let port: UInt16
    private let queue = DispatchQueue(label: "pointerpad.control.send")

    init(port: UInt16) {
        self.port = port
    }

    func send(type: String, payload: [String: Any], to host: String, token: String) {
        guard let port = NWEndpoint.Port(rawValue: port) else { return }
        var dict = payload
        dict["type"] = type
        dict["token"] = token
        guard let data = try? JSONSerialization.data(withJSONObject: dict, options: []) else { return }

        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .udp)
        connection.start(queue: queue)
        connection.send(content: data, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }
}

final class UdpServer {
    private let config: Config
    private let mouse: MouseController
    private let drawingView: DrawingView
    private let clientRegistry: ClientRegistry
    private let queue = DispatchQueue(label: "mac.pointer.server")
    private var listener: NWListener?

    init(config: Config, drawingView: DrawingView, clientRegistry: ClientRegistry) {
        self.config = config
        self.mouse = MouseController(sensitivity: config.sensitivity, useWarp: config.useWarp, invertY: config.invertY)
        self.drawingView = drawingView
        self.clientRegistry = clientRegistry
    }

    func start() throws {
        guard let port = NWEndpoint.Port(rawValue: config.port) else {
            throw NSError(domain: "MacPointerServer", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid port"])
        }

        let parameters = NWParameters.udp
        let listener = try NWListener(using: parameters, on: port)
        listener.newConnectionHandler = { [weak self] connection in
            connection.start(queue: self?.queue ?? .main)
            self?.receive(on: connection)
        }
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                print("Listening on UDP :\(port)")
            case .failed(let error):
                print("Listener failed: \(error)")
            default:
                break
            }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    private func receive(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, _, error in
            if let data = data, !data.isEmpty {
                let endpoint = connection.endpoint
                self?.handle(data: data, from: endpoint)
            }
            if error == nil {
                self?.receive(on: connection)
            }
        }
    }

    private func handle(data: Data, from endpoint: NWEndpoint) {
        if case let .hostPort(host, _) = endpoint {
            clientRegistry.update(host: String(describing: host))
        }
        guard
            let json = try? JSONSerialization.jsonObject(with: data, options: []),
            let dict = json as? [String: Any]
        else { return }

        if !config.token.isEmpty {
            let token = dict["token"] as? String ?? ""
            if token != config.token {
                if config.verbose {
                    print("Drop packet from \(endpoint): token mismatch")
                }
                return
            }
        }

        let type = (dict["type"] as? String ?? "").lowercased()
        if type == "test" {
            if config.verbose {
                print("Test packet from \(endpoint)")
            }
            return
        }

        switch type {
        case "move":
            let dx = dict["dx"] as? Double ?? 0
            let dy = dict["dy"] as? Double ?? 0
            if config.verbose {
                print("Move from \(endpoint): dx=\(dx) dy=\(dy)")
            }
            mouse.move(dx: dx, dy: dy)
        case "button":
            let button = dict["button"] as? String ?? "left"
            let state = dict["state"] as? String ?? "down"
            if config.verbose {
                print("Button from \(endpoint): \(button) \(state)")
            }
            mouse.setButton(button: button, isDown: state == "down")
        case "scroll":
            let dx = dict["dx"] as? Double ?? 0
            let dy = dict["dy"] as? Double ?? 0
            if config.verbose {
                print("Scroll from \(endpoint): dx=\(dx) dy=\(dy)")
            }
            mouse.scroll(dx: dx, dy: dy)
        case "draw_begin", "draw_move", "draw_end", "draw_undo", "draw_clear", "draw_erase", "draw_view", "image_move", "image_resize":
            handleDraw(type: type, dict: dict)
        default:
            if config.verbose {
                print("Unknown packet from \(endpoint): \(dict)")
            }
        }
    }

    private func handleDraw(type: String, dict: [String: Any]) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            switch type {
            case "draw_undo":
                self.drawingView.undo()
                return
            case "draw_clear":
                self.drawingView.clear()
                return
            case "draw_view":
                let offsetX = dict["offset_x"] as? Double ?? 0
                let offsetY = dict["offset_y"] as? Double ?? 0
                let scale = dict["scale"] as? Double ?? 1.0
                let viewW = dict["view_w"] as? Double ?? 1.0
                let viewH = dict["view_h"] as? Double ?? 1.0
                self.drawingView.updateView(
                    offset: CGPoint(x: offsetX, y: offsetY),
                    scale: CGFloat(scale),
                    sourceSize: CGSize(width: viewW, height: viewH)
                )
                return
            case "draw_erase":
                let x = dict["x"] as? Double ?? 0
                let y = dict["y"] as? Double ?? 0
                let radius = dict["radius"] as? Double ?? 8.0
                self.drawingView.erase(at: CGPoint(x: x, y: y), radius: CGFloat(radius))
                return
            case "image_move":
                let id = String(describing: dict["id"] ?? "")
                let x = dict["x"] as? Double ?? 0
                let y = dict["y"] as? Double ?? 0
                self.drawingView.moveImage(id: id, x: CGFloat(x), y: CGFloat(y))
                return
            case "image_resize":
                let id = String(describing: dict["id"] ?? "")
                let w = dict["width"] as? Double ?? 1
                let h = dict["height"] as? Double ?? 1
                self.drawingView.resizeImage(id: id, width: CGFloat(w), height: CGFloat(h))
                return
            default:
                break
            }

            let idValue = dict["id"]
            let id = String(describing: idValue ?? "")
            let x = dict["x"] as? Double ?? 0
            let y = dict["y"] as? Double ?? 0
            let point = CGPoint(x: x, y: y)

            if type == "draw_begin" {
                let colorInt = dict["color"] as? Int ?? 0xFF000000
                let width = dict["width"] as? Double ?? 2.0
                self.drawingView.beginStroke(id: id, point: point, color: nsColor(from: colorInt), width: CGFloat(width))
            } else if type == "draw_move" {
                self.drawingView.appendPoint(id: id, point: point)
            } else if type == "draw_end" {
                self.drawingView.endStroke(id: id)
            }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let drawingView: DrawingView
    private let imageSender: ImageSender
    private let controlSender: ControlSender
    private var window: NSWindow?
    private let clientRegistry: ClientRegistry
    private var token: String
    private let verbose: Bool
    private var keyMonitor: Any?

    init(drawingView: DrawingView, imageSender: ImageSender, controlSender: ControlSender, clientRegistry: ClientRegistry, token: String, verbose: Bool) {
        self.drawingView = drawingView
        self.imageSender = imageSender
        self.controlSender = controlSender
        self.clientRegistry = clientRegistry
        self.token = token
        self.verbose = verbose
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let frame = NSRect(x: 200, y: 200, width: 900, height: 600)
        let window = NSWindow(
            contentRect: frame,
            styleMask: [.titled, .resizable, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "PointerPad Viewer"
        window.contentView = drawingView
        window.makeKeyAndOrderFront(nil)
        self.window = window

        let pasteItem = NSMenuItem(title: "Paste Image", action: #selector(pasteImage), keyEquivalent: "v")
        pasteItem.keyEquivalentModifierMask = [.command]
        let menu = NSMenu()
        let appItem = NSMenuItem()
        appItem.submenu = NSMenu(title: "PointerPad Viewer")
        appItem.submenu?.addItem(pasteItem)
        menu.addItem(appItem)
        NSApp.mainMenu = menu

        drawingView.onImageMove = { [weak self] id, x, y in
            self?.sendControl(type: "image_move", payload: ["id": id, "x": x, "y": y])
        }
        drawingView.onImageResize = { [weak self] id, width, height in
            self?.sendControl(type: "image_resize", payload: ["id": id, "width": width, "height": height])
        }

        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self = self else { return event }
            let cmd = event.modifierFlags.contains(.command)
            if cmd && event.charactersIgnoringModifiers?.lowercased() == "v" {
                self.pasteImage()
                return nil
            }
            return event
        }
    }

    @objc private func pasteImage() {
        let pasteboard = NSPasteboard.general
        guard let image = imageFromPasteboard(pasteboard) else {
            print("No image on clipboard.")
            return
        }
        guard let prepared = imageSender.prepare(image: image) else {
            print("Failed to prepare image.")
            return
        }
        drawingView.addImage(id: prepared.id, image: prepared.image, x: 0, y: 0, width: prepared.width, height: prepared.height)

        if let host = clientRegistry.getHost() {
            imageSender.send(prepared: prepared, to: host, token: token)
        } else {
            print("No phone connected; image shown locally only.")
        }
    }

    private func imageFromPasteboard(_ pasteboard: NSPasteboard) -> NSImage? {
        if verbose {
            let types = pasteboard.types?.map { $0.rawValue } ?? []
            print("Pasteboard types: \(types)")
        }
        if let url = fileURLFromPasteboard(pasteboard) {
            let resolved = resolveAlias(url)
            if let image = imageFromURL(resolved) {
                return image
            }
        }

        if let data = pasteboard.data(forType: .png) ?? pasteboard.data(forType: .tiff),
           let image = NSImage(data: data) {
            return image
        }

        if let image = (pasteboard.readObjects(forClasses: [NSImage.self], options: nil) as? [NSImage])?.first {
            return image
        }

        return NSImage(pasteboard: pasteboard)
    }

    private func fileURLFromPasteboard(_ pasteboard: NSPasteboard) -> URL? {
        if let filenames = pasteboard.propertyList(forType: NSPasteboard.PasteboardType("NSFilenamesPboardType")) as? [String],
           let first = filenames.first {
            return URL(fileURLWithPath: first)
        }
        if let urlString = pasteboard.string(forType: .fileURL),
           let url = URL(string: urlString) {
            return url
        }
        if let urls = pasteboard.readObjects(
            forClasses: [NSURL.self],
            options: [.urlReadingFileURLsOnly: true]
        ) as? [URL] {
            return urls.first
        }
        return nil
    }

    private func resolveAlias(_ url: URL) -> URL {
        do {
            return try URL(resolvingAliasFileAt: url, options: .withoutUI)
        } catch {
            return url
        }
    }

    private func imageFromURL(_ url: URL) -> NSImage? {
        if let source = CGImageSourceCreateWithURL(url as CFURL, nil),
           let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil) {
            return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
        }
        if let data = try? Data(contentsOf: url),
           let image = NSImage(data: data) {
            return image
        }
        return NSImage(contentsOf: url)
    }

    private func sendControl(type: String, payload: [String: Any]) {
        guard let host = clientRegistry.getHost() else { return }
        controlSender.send(type: type, payload: payload, to: host, token: token)
    }
}

func parseConfig() -> Config {
    var port: UInt16 = 50505
    var token = ""
    var sensitivity = 1.2
    var verbose = false
    var useWarp = false
    var invertY = false
    var imagePort: UInt16 = 50506
    var imageMaxSize: CGFloat = 1600
    var controlPort: UInt16 = 50507

    let args = CommandLine.arguments
    var i = 1
    while i < args.count {
        switch args[i] {
        case "--port":
            if i + 1 < args.count { port = UInt16(args[i + 1]) ?? port; i += 1 }
        case "--token":
            if i + 1 < args.count { token = args[i + 1]; i += 1 }
        case "--sensitivity":
            if i + 1 < args.count { sensitivity = Double(args[i + 1]) ?? sensitivity; i += 1 }
        case "--verbose":
            verbose = true
        case "--warp":
            useWarp = true
        case "--invert-y":
            invertY = true
        case "--image-port":
            if i + 1 < args.count { imagePort = UInt16(args[i + 1]) ?? imagePort; i += 1 }
        case "--image-max":
            if i + 1 < args.count { imageMaxSize = CGFloat(Double(args[i + 1]) ?? Double(imageMaxSize)); i += 1 }
        case "--control-port":
            if i + 1 < args.count { controlPort = UInt16(args[i + 1]) ?? controlPort; i += 1 }
        default:
            break
        }
        i += 1
    }

    if token.isEmpty {
        token = ProcessInfo.processInfo.environment["POINTER_TOKEN"] ?? ""
    }

    return Config(
        port: port,
        token: token,
        sensitivity: sensitivity,
        verbose: verbose,
        useWarp: useWarp,
        invertY: invertY,
        imagePort: imagePort,
        imageMaxSize: imageMaxSize,
        controlPort: controlPort
    )
}

func nsColor(from argb: Int) -> NSColor {
    let a = CGFloat((argb >> 24) & 0xFF) / 255.0
    let r = CGFloat((argb >> 16) & 0xFF) / 255.0
    let g = CGFloat((argb >> 8) & 0xFF) / 255.0
    let b = CGFloat(argb & 0xFF) / 255.0
    return NSColor(calibratedRed: r, green: g, blue: b, alpha: a)
}

let config = parseConfig()
let drawingView = DrawingView(frame: NSRect(x: 0, y: 0, width: 900, height: 600))
let clientRegistry = ClientRegistry()
let server = UdpServer(config: config, drawingView: drawingView, clientRegistry: clientRegistry)
let imageSender = ImageSender(port: config.imagePort, maxSize: config.imageMaxSize)
let controlSender = ControlSender(port: config.controlPort)
let delegate = AppDelegate(drawingView: drawingView, imageSender: imageSender, controlSender: controlSender, clientRegistry: clientRegistry, token: config.token, verbose: config.verbose)

let app = NSApplication.shared
app.setActivationPolicy(.regular)
app.delegate = delegate

if config.verbose {
    print("Token: \(config.token.isEmpty ? "<none>" : "set")")
    print("Sensitivity: \(config.sensitivity)")
}
print("Grant Accessibility permissions for mouse control if prompted.")

do {
    try server.start()
    app.activate(ignoringOtherApps: true)
    app.run()
} catch {
    print("Failed to start server: \(error)")
}
