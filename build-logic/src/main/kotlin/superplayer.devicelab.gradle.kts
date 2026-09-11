// Device measurement, applied to the root project only. devicelab/README.md is the manual.
//
// These need a device, so none of them is part of `check`, and nothing `check` runs depends on them:
// `check` has to pass on a machine with no device attached, and docs/testing.md bars a device from the
// test suite. They are Gradle tasks anyway for the reason CLAUDE.md gives for checks — one named entry
// point that a laptop and CI agree on — and each is exactly the `devicelab/lab` command line the
// scheduled workflow runs, with the scenario's own defaults, so the two cannot drift apart.
//
// Never up to date: the device, the network and the state of the working tree are all inputs that
// Gradle cannot see, and a measurement skipped because nothing it could see changed would be wrong.

tasks.register<Exec>("huntLeaks") {
    group = "devicelab"
    description =
        "Drives the demo through the leak hunt on a device and reports what grew and what retains it " +
        "(devicelab/leak/README.md). Needs a device or an emulator; not part of check."
    workingDir(layout.projectDirectory)
    commandLine(layout.projectDirectory.file("devicelab/lab").asFile.absolutePath, "run", "leak-hunt")
    outputs.upToDateWhen { false }
}
