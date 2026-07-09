import Foundation

/// Short motivational lines for the lock-screen widget — rotated by day of
/// year, deterministically (no Date.now in the view; the timeline entry's date
/// picks the quote, so the midnight entry rolls it over).
enum Quotes {
    static let all: [String] = [
        "The race is won in training.",
        "Consistency beats intensity.",
        "You don't have to be fast. You have to be relentless.",
        "One workout at a time.",
        "Swim strong, ride smart, run brave.",
        "The hard days are the ones that count.",
        "Motivation fades. Discipline shows up anyway.",
        "Trust the plan. Do the work.",
        "Miles now, medals later.",
        "Your only competition is yesterday's you.",
        "Smooth is fast.",
        "The body achieves what the mind believes.",
        "Rest is training too.",
        "Show up. Especially today.",
        "Every session is a brick in the wall.",
        "Race day is just a victory lap for training.",
        "Pain is temporary. Finish lines are forever.",
        "Strong is built in silence.",
        "Don't count the days — make the days count.",
        "Anything is possible.",
    ]

    static func ofTheDay(for date: Date) -> String {
        let day = Calendar.current.ordinality(of: .day, in: .year, for: date) ?? 1
        return all[day % all.count]
    }
}
