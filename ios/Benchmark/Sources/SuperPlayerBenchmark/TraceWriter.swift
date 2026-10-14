// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import Foundation
import SuperPlayerCore

/// Every event of every run, written out beside the summary — the Kotlin `TraceWriter`, for
/// `PRD.md` §6's rule: **publish the raw traces**, because a summary is an argument and a trace is
/// evidence.
///
/// ## The format
///
/// JSONL, one file per cell named as the Kotlin one is, one line per event carrying its cell and
/// run index beside the event's own fields, so `grep '"evt":"rebufferEnded"'` is a class of event,
/// `grep '"run":3'` one session, and files concatenate without losing what they were.
///
/// **The event's fields are its own Swift names, read by reflection**, where the Kotlin writer
/// spells `LogcatSink`'s names by hand. iOS's line-per-event sink, `OSLogSink`, keeps its format
/// internal to `SuperPlayerTelemetry`, and a second hand-written spelling here would be one more
/// thing to drift; reading the payload's own properties writes every field a case carries, a field
/// added later included, with nothing to keep in step. The keys are sorted, so a line is the same
/// bytes run after run. No dependency, for the Kotlin reason: JSON is `JSONSerialization`'s.
enum TraceWriter {

    /// Writes one JSONL file for `key` into `directory`; `runs` is the cell's sessions in order,
    /// each one session's events.
    static func write(_ key: CellKey, runs: [[TelemetryEvent]], into directory: URL) throws {
        var text = ""
        for (run, events) in runs.enumerated() {
            for event in events { text += try line(event, key: key, run: run) + "\n" }
        }
        try text.write(
            to: directory.appendingPathComponent(key.traceFileName), atomically: true,
            encoding: .utf8)
    }

    /// One event as one line.
    static func line(_ event: TelemetryEvent, key: CellKey, run: Int) throws -> String {
        var object: [String: Any] = [
            "scenario": key.scenario.label, "network": MatrixNetwork.label(key.network),
            "arm": key.arm.label, "run": run, "v": event.schemaVersion,
        ]
        let mirror = Mirror(reflecting: event)
        guard let (name, payload) = mirror.children.first.map({ ($0.label ?? "?", $0.value) })
        else { return "" }
        object["evt"] = name
        if let fields = json(payload) as? [String: Any] {
            object.merge(fields) { mine, _ in mine }
        }
        let data = try JSONSerialization.data(
            withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        return String(decoding: data, as: UTF8.self)
    }

    /// `value` as something `JSONSerialization` takes: a number, a string or a boolean as itself, an
    /// optional unwrapped or null, a structure as an object of its stored properties, and anything
    /// else — an enumeration case — as its name.
    private static func json(_ value: Any) -> Any {
        switch value {
        case let value as Bool: return value
        case let value as Int: return value
        case let value as Int64: return value
        case let value as Double: return value
        case let value as String: return value
        default: break
        }
        let mirror = Mirror(reflecting: value)
        switch mirror.displayStyle {
        case .optional:
            return mirror.children.first.map { json($0.value) } ?? NSNull()
        case .struct, .class:
            var object: [String: Any] = [:]
            for child in mirror.children {
                if let label = child.label { object[label] = json(child.value) }
            }
            return object
        default:
            return String(describing: value)
        }
    }
}
