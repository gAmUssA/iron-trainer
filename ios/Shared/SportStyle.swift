import SwiftUI

/// Single source of truth for sport colors + icons, shared by the app and the
/// widget extension. Colors match the web app's chart palette; plain literals
/// (no asset catalog) so both targets compile them without duplication.
enum SportStyle {
    /// Brand accent (web --accent #ff5d3b) — the one tint for primary actions.
    static let accent = Color(red: 1.0, green: 0.365, blue: 0.231)

    static func color(for sport: String?) -> Color {
        switch sport {
        case "Swim": return Color(red: 0.220, green: 0.741, blue: 0.973)   // #38bdf8
        case "Bike", "Brick": return Color(red: 1.0, green: 0.706, blue: 0.329) // #ffb454
        case "Run": return Color(red: 0.290, green: 0.871, blue: 0.502)    // #4ade80
        case "Strength": return Color(red: 0.753, green: 0.518, blue: 0.988) // #c084fc
        default: return Color(red: 0.607, green: 0.639, blue: 0.686)       // neutral slate
        }
    }

    static func icon(for sport: String?) -> String {
        switch sport {
        case "Swim": return "figure.pool.swim"
        case "Bike", "Brick": return "figure.outdoor.cycle"
        case "Run": return "figure.run"
        case "Strength": return "figure.strengthtraining.traditional"
        default: return "figure.mixed.cardio"
        }
    }
}
