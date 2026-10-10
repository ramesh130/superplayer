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

// Reduces a `swift-api-digester -dump-sdk` JSON dump to the text `ios/api/<Product>.baseline`
// holds: one line per public declaration, sorted. `ios/check` runs it; docs/api-surface.md's iOS
// section says why the dump is reduced rather than committed as it comes.
//
//     swift ios/scripts/api-surface.swift <directory> <Product>...
//
// reads `<directory>/<Product>.json` and writes `<directory>/<Product>.baseline` for each product,
// in one invocation because `swift` compiles a script before it runs it.
//
// What a line keeps is what an adopter's code can depend on: the declaration's kind, its path,
// its signature's types, its generic signature, whether it is static, throwing, mutating or
// settable, and a type's conformances. What it drops is what moves with the compiler rather than
// with this package: mangled names and USRs, the dump's own format version and tool arguments,
// the implicit imports every module carries, and the marker protocols a newer compiler infers on
// every type (`implicitConformances`). Lines are sorted rather than kept in dump order, because
// the order is the digester's and not a fact about the API.

import Foundation

/// Conformances the compiler adds to every eligible type by itself, and which a newer compiler
/// adds more of: `Copyable` and `Escapable` arrived with noncopyable and nonescapable types and
/// `SendableMetatype` with Swift 6.2. Recording them would make a baseline a reading of the Xcode
/// that wrote it, so a baseline written on one machine would fail on another for nothing an adopter
/// can see. `Sendable` is not here: it is declared, and it is a promise.
let implicitConformances: Set<String> = [
    "Copyable", "Escapable", "SendableMetatype", "BitwiseCopyable",
]

/// Node kinds that are a type in a signature rather than a declaration of their own.
let typeKinds: Set<String> = ["TypeNominal", "TypeFunc", "TypeNameAlias"]

/// The boolean facts about a declaration an adopter's call site depends on.
let flags = ["static", "throwing", "isOpen", "hasDefaultArg"]

func declarationLines(_ node: [String: Any], path: [String]) -> [String] {
    let kind = node["kind"] as? String ?? ""
    let children = node["children"] as? [[String: Any]] ?? []
    if kind == "Root" {
        return children.flatMap { declarationLines($0, path: path) }
    }
    if kind == "Import" || typeKinds.contains(kind) {
        return []
    }
    let printedName = node["printedName"] as? String ?? node["name"] as? String ?? "?"
    let here = path + [printedName]
    var line = "\(node["declKind"] as? String ?? kind) \(here.joined(separator: "."))"
    let signature = children.filter { typeKinds.contains($0["kind"] as? String ?? "") }
        .map { $0["printedName"] as? String ?? "?" }
    if !signature.isEmpty {
        // The digester lists a function's result first and its parameters after, and a variable's
        // or a subscript's type alone; both are kept in that order.
        line += " : " + signature.joined(separator: ", ")
    }
    if let generics = node["genericSig"] as? String { line += " where \(generics)" }
    for flag in flags where node[flag] as? Bool == true { line += " [\(flag)]" }
    if let selfKind = node["funcSelfKind"] as? String, selfKind != "NonMutating" {
        line += " [\(selfKind)]"
    }
    let accessors = (node["accessors"] as? [[String: Any]] ?? [])
        .compactMap { $0["accessorKind"] as? String }
    if !accessors.isEmpty { line += " { \(accessors.joined(separator: " ")) }" }
    let conformances = (node["conformances"] as? [[String: Any]] ?? [])
        .compactMap { $0["printedName"] as? String }
        .filter { !implicitConformances.contains($0) }
        .sorted()
    if !conformances.isEmpty { line += " : \(conformances.joined(separator: ", "))" }
    let nested = children.filter { !typeKinds.contains($0["kind"] as? String ?? "") }
    return [line] + nested.flatMap { declarationLines($0, path: here) }
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("api-surface.swift: \(message)\n".utf8))
    exit(2)
}

let arguments = CommandLine.arguments
guard arguments.count >= 3 else { fail("usage: api-surface.swift <directory> <Product>...") }
let directory = URL(fileURLWithPath: arguments[1])
for product in arguments.dropFirst(2) {
    let dump = directory.appendingPathComponent("\(product).json")
    guard let data = FileManager.default.contents(atPath: dump.path),
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
        let root = json["ABIRoot"] as? [String: Any]
    else { fail("\(dump.path) is not a swift-api-digester dump") }
    let lines =
        ["// \(product): public API as ios/check reads it. Regenerate with ios/check --update-api."]
        + declarationLines(root, path: []).sorted()
    let baseline = directory.appendingPathComponent("\(product).baseline")
    try (lines.joined(separator: "\n") + "\n").write(
        to: baseline, atomically: true, encoding: .utf8)
}
