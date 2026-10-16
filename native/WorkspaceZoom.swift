import AppKit
import WebKit

/// Page zoom covers the workspace and embedded database UI without modifying either DOM.
enum WorkspaceZoom {
    static let preferenceKey = "workspaceZoomPercent"
    static let range = 50...200
    static func normalized(_ percent: Int) -> Int { min(range.upperBound, max(range.lowerBound, percent == 0 ? 100 : percent)) }
    static func next(_ percent: Int, direction: Int) -> Int { normalized(normalized(percent) + direction * 10) }

    // Recognize both the printed '+' and the unshifted '=' key, including numeric keyboards.
    // Control/Option combinations belong to macOS or to the focused editor/shell.
    static func shortcut(_ event: NSEvent) -> Int? {
        let modifiers = event.modifierFlags.intersection([.command, .control, .option, .shift])
        guard modifiers == .command || modifiers == [.command, .shift] else { return nil }
        switch event.charactersIgnoringModifiers {
        case "+", "=": return 1
        case "-" where modifiers == .command: return -1
        case "0" where modifiers == .command: return 0
        default: return nil
        }
    }
}

final class WorkspaceWebView: WKWebView {
    var zoomCommand: ((Int) -> Void)?
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        if let direction = WorkspaceZoom.shortcut(event), let command = zoomCommand {
            command(direction)
            return true
        }
        return super.performKeyEquivalent(with: event)
    }
}
