import SwiftUI
import WidgetKit

@main
struct IronTrainerWidgetBundle: WidgetBundle {
    var body: some Widget {
        RaceCountdownWidget()
        TodayWorkoutWidget()
    }
}
