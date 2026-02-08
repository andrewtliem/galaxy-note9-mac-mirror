// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MacPointerServer",
    platforms: [
        .macOS(.v12)
    ],
    products: [
        .executable(name: "MacPointerServer", targets: ["MacPointerServer"])
    ],
    targets: [
        .executableTarget(
            name: "MacPointerServer",
            dependencies: []
        )
    ]
)
