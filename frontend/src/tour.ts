// Guided product tour using WalkthroughJS, covering every screen/section.
//
// Steps target stable element ids on each card. Sections render conditionally
// (charts only appear once activities are synced), so we filter the step list
// to elements actually present in the DOM before starting — that way the tour
// adapts to whether you've connected Strava and generated a plan yet.

import Walkthrough, { type WalkthroughStep } from "@ronanarm/walkthroughjs";

// The app is tabbed; the tour runs on the Dashboard tab and points at the nav
// to introduce the other screens (Training Plan / Fitness / Nutrition / Tests /
// Settings). The PMC fitness/form chart lives on the Fitness tab, so it's
// introduced via the nav step; race readiness is on the Dashboard and anchored
// directly.
const ALL_STEPS: WalkthroughStep[] = [
  {
    element: "#tour-countdown",
    title: "Race countdown",
    text: "Days remaining until IRONMAN 70.3 New York. Everything here is built around this date.",
    position: "left",
  },
  {
    element: "#tour-nav",
    title: "Your screens",
    text: "Dashboard (today's call, race readiness, records & check-in), Training Plan, Fitness, Nutrition, Tests and Settings. Fitness holds the PMC fitness/form chart and sport trends — the same TrainingPeaks CTL/ATL/TSB model. Explore each after the tour.",
    position: "bottom",
  },
  {
    element: "#tour-setup",
    title: "Connect & sync",
    text: "Your Strava connection status. Connect Strava, pull your history and pair the iOS app over in the Settings tab. Activities recorded by two devices are de-duplicated there (bike → Garmin Edge, swim/run → Apple Watch).",
    position: "bottom",
  },
  {
    element: "#tour-readiness",
    title: "Race readiness",
    text: "Projected 70.3 finish at current fitness, a timeline of your splits, and the official cut-offs (swim 1:10, bike 5:30, finish 8:30) with your margin on each.",
    position: "left",
  },
];

function visibleSteps(): WalkthroughStep[] {
  return ALL_STEPS.filter((s) => document.querySelector(s.element));
}

export function startTour(): void {
  const steps = visibleSteps();
  if (!steps.length) return;
  const tour = new Walkthrough({
    progressColor: "#ff6b35",
    overlayColor: "rgba(0,0,0,0.6)",
    highlightPadding: 12,
  });
  tour.configure({ steps });
  tour.start();
}
