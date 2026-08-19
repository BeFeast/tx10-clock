// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TX10ClockCore",
    platforms: [
        .tvOS(.v17),
        .macOS(.v13),
    ],
    products: [
        .library(name: "TX10ClockCore", targets: ["TX10ClockCore"]),
    ],
    targets: [
        .target(name: "TX10ClockCore"),
        .testTarget(name: "TX10ClockCoreTests", dependencies: ["TX10ClockCore"]),
    ]
)
