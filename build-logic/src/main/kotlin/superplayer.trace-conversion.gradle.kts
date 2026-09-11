import com.superplayer.build.ConvertThroughputTrace

// Repository tooling, applied to the root project only: converts a public throughput dataset's file
// into the trace format `superplayer-testkit` replays. See docs/throughput-traces.md.

tasks.register<ConvertThroughputTrace>("convertThroughputTrace") {
    group = "superplayer"
    description = "Converts a public throughput dataset file into SuperPlayer's trace format."
    projectRoot.set(layout.projectDirectory)
}
