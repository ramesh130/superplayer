import com.superplayer.build.ConvertThroughputTrace
import com.superplayer.build.repositoryRoot

// Repository tooling, applied to the root project only: converts a public throughput dataset's file
// into the trace format `superplayer-testkit` replays. See docs/throughput-traces.md.

tasks.register<ConvertThroughputTrace>("convertThroughputTrace") {
    group = "superplayer"
    description = "Converts a public throughput dataset file into SuperPlayer's trace format."
    repositoryRoot.set(project.repositoryRoot)
}
