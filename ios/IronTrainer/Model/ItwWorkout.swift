import Foundation

/// Decodes the Iron Trainer Workout (.itw) JSON emitted by the backend
/// (`backend/app/export/itw_export.py`). This is *our* intermediate format, not
/// Apple's binary `.workout` — the native workout is built on-device from this.
struct ItwWorkout: Codable, Equatable {
    /// Highest schema major version this build understands.
    static let supportedSchemaVersion = 1

    let schemaVersion: Int
    let generator: String?
    let date: String?          // ISO YYYY-MM-DD — drives scheduling
    let sport: String?         // Swim | Bike | Run | Brick | Strength
    let title: String?
    let description: String?
    let durationS: Int?
    let distanceM: Double?
    let athlete: Athlete?
    let steps: [Step]

    struct Athlete: Codable, Equatable {
        let ftp: Double?
        let thresholdHr: Int?
        let maxHr: Int?
        let thresholdPaceRun: Double?  // sec/km
        let cssSwim: Double?           // sec/100m

        enum CodingKeys: String, CodingKey {
            case ftp
            case thresholdHr = "threshold_hr"
            case maxHr = "max_hr"
            case thresholdPaceRun = "threshold_pace_run"
            case cssSwim = "css_swim"
        }
    }

    struct Step: Codable, Equatable {
        let type: String?        // warmup | cooldown | recovery | interval | steady
        let durationS: Int?
        let distanceM: Double?
        let notes: String?
        let target: Target?

        enum CodingKeys: String, CodingKey {
            case type
            case durationS = "duration_s"
            case distanceM = "distance_m"
            case notes
            case target
        }
    }

    struct Target: Codable, Equatable {
        let type: String?        // power | pace | hr | open
        let low: Double?
        let high: Double?
        let unit: String?        // sec_per_km | sec_per_100m | nil
    }

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case generator, date, sport, title, description, athlete, steps
        case durationS = "duration_s"
        case distanceM = "distance_m"
    }
}

enum ItwError: LocalizedError {
    case unsupportedSchema(found: Int, supported: Int)

    var errorDescription: String? {
        switch self {
        case let .unsupportedSchema(found, supported):
            return "This .itw file is version \(found); this app supports up to \(supported). Please update Iron Trainer."
        }
    }
}

extension ItwWorkout {
    /// Decode a `.itw` file and reject unknown major schema versions gracefully.
    static func decode(from data: Data) throws -> ItwWorkout {
        let workout = try JSONDecoder().decode(ItwWorkout.self, from: data)
        guard workout.schemaVersion <= supportedSchemaVersion else {
            throw ItwError.unsupportedSchema(found: workout.schemaVersion,
                                             supported: supportedSchemaVersion)
        }
        return workout
    }

    /// The planned date ("YYYY-MM-DD") parsed to a local `Date` (midnight), if present.
    var plannedDate: Date? {
        guard let date else { return nil }
        let parts = date.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var comps = DateComponents()
        comps.year = parts[0]; comps.month = parts[1]; comps.day = parts[2]
        return Calendar.current.date(from: comps)
    }
}
