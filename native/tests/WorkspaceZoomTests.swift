import AppKit

@main
struct WorkspaceZoomTests {
    static func main() {
        precondition(WorkspaceZoom.normalized(0) == 100)
        precondition(WorkspaceZoom.normalized(500) == 200)
        precondition(WorkspaceZoom.normalized(-5) == 50)
        precondition(WorkspaceZoom.next(100, direction: 1) == 110)
        precondition(WorkspaceZoom.next(100, direction: -1) == 90)
        precondition(WorkspaceZoom.next(200, direction: 1) == 200)
        precondition(WorkspaceZoom.next(50, direction: -1) == 50)
        func shortcut(_ key: String, _ flags: NSEvent.ModifierFlags) -> Int? {
            let event = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: flags, timestamp: 0, windowNumber: 0, context: nil, characters: key, charactersIgnoringModifiers: key, isARepeat: false, keyCode: 0)!
            return WorkspaceZoom.shortcut(event)
        }
        precondition(shortcut("+", [.command, .shift]) == 1)
        precondition(shortcut("=", .command) == 1)
        precondition(shortcut("=", [.command, .shift]) == 1)
        precondition(shortcut("-", .command) == -1)
        precondition(shortcut("0", .command) == 0)
        precondition(shortcut("+", [.command, .numericPad]) == 1)
        precondition(shortcut("=", [.command, .control]) == nil)
        precondition(shortcut("=", [.command, .option]) == nil)
        precondition(shortcut("-", [.command, .shift]) == nil)
        precondition(shortcut("=", []) == nil)
        print("Native zoom checks passed")
    }
}
