import SwiftUI

/// The interval profile as variable-width bars: width ∝ step time share, height
/// and opacity ∝ intensity vs threshold. Pure SwiftUI (no Swift Charts) so it
/// renders identically inside the widget extension, where every byte counts.
struct WorkoutProfileChart: View {
    let segments: [ProfileSegment]
    let sportColor: Color
    var height: CGFloat = 72

    var body: some View {
        GeometryReader { geo in
            let totalWeight = max(segments.reduce(0) { $0 + $1.weight }, 1)
            let spacing: CGFloat = 1.5
            let available = geo.size.width - spacing * CGFloat(max(segments.count - 1, 0))
            HStack(alignment: .bottom, spacing: spacing) {
                ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                    let frac = max(seg.weight / totalWeight, 0.03) // short recoveries stay visible
                    RoundedRectangle(cornerRadius: 2.5)
                        .fill(fill(for: seg))
                        .frame(width: max(available * frac, 3),
                               height: geo.size.height * barHeight(for: seg))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        .frame(height: height)
        .accessibilityLabel("Workout intensity profile, \(segments.count) steps")
    }

    private func barHeight(for seg: ProfileSegment) -> CGFloat {
        guard let i = seg.intensity else { return WorkoutIntensity.openHeight }
        return CGFloat(i / WorkoutIntensity.displayMax)
    }

    private func fill(for seg: ProfileSegment) -> Color {
        guard let i = seg.intensity else { return sportColor.opacity(0.25) }
        // Easy work fades, hard work saturates; warmup/cooldown/recovery muted.
        let normalized = (i - WorkoutIntensity.displayMin)
            / (WorkoutIntensity.displayMax - WorkoutIntensity.displayMin)
        let easing = ["warmup", "cooldown", "recovery"].contains(seg.kind ?? "") ? 0.75 : 1.0
        return sportColor.opacity((0.35 + 0.65 * normalized) * easing)
    }
}
