import SwiftUI

extension View {
    /// Primary-action treatment for buttons on the navigation layer (e.g. the
    /// Schedule buttons floating over a scrolling list). On iOS 26+ this is the
    /// prominent Liquid Glass style; before that it falls back to bordered-prominent
    /// (which itself adapts to glass when available). Reserve for *one* primary
    /// action per screen — tinting/emphasising everything destroys hierarchy.
    @ViewBuilder
    func primaryActionButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent)
        } else {
            self.buttonStyle(.borderedProminent)
        }
    }
}
