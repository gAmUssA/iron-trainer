import Foundation

/// The backend appends a one-line fueling summary ("Fuel: 60 g carbs/h (~3
/// gels/h) · 750 mL fluid/h …") to workout descriptions. Pull it out so the UI
/// can show it as a first-class row instead of buried prose.
enum FuelParser {
    static func fuelLine(from description: String?) -> String? {
        guard let description else { return nil }
        for line in description.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.lowercased().hasPrefix("fuel:") {
                let value = trimmed.dropFirst("fuel:".count).trimmingCharacters(in: .whitespaces)
                return value.isEmpty ? nil : value
            }
        }
        return nil
    }
}
