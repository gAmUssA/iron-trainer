import Foundation

/// Distance-unit preference for browsing the plan (the backend and .itw files
/// stay metric; this is display-only). Swim paces stay per-100m in either mode —
/// that's the universal pool convention.
enum DistanceUnit: String, CaseIterable, Identifiable {
    case km
    case mi

    var id: String { rawValue }
    var label: String { rawValue }

    static let storageKey = "iron.distanceUnit"
    static let metersPerMile = 1609.344
}

enum UnitFormat {
    /// "12.5 km" / "7.8 mi" for run/bike; swim distances and short efforts
    /// (<800 m) read best in meters regardless of the preference.
    static func distance(meters: Double, unit: DistanceUnit, sport: String? = nil) -> String {
        if sport == "Swim" || meters < 800 { return "\(Int(meters)) m" }
        switch unit {
        case .km: return String(format: "%.1f km", meters / 1000)
        case .mi: return String(format: "%.1f mi", meters / DistanceUnit.metersPerMile)
        }
    }

    /// A pace range like "5:53–6:24 /km" (or /mi), from sec-per-km bounds.
    static func paceRange(lowSecPerKm: Double, highSecPerKm: Double, unit: DistanceUnit) -> String {
        let factor = unit == .mi ? DistanceUnit.metersPerMile / 1000 : 1
        return "\(mmss(lowSecPerKm * factor))–\(mmss(highSecPerKm * factor)) /\(unit.label)"
    }

    /// A swim pace range like "1:38–1:45 /100m" (unit-independent).
    static func swimPaceRange(lowSecPer100: Double, highSecPer100: Double) -> String {
        "\(mmss(lowSecPer100))–\(mmss(highSecPer100)) /100m"
    }

    static func mmss(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}
