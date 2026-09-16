/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * superplayer-resilience — ErrorClassifier, RetryPolicy, FallbackLadder (CDN/variant/protocol)
 *
 * Phase 5, decided in full by ADR-0011 before any of it landed. What is here so far is the taxonomy:
 * `FailureClass`, the vocabulary a failure is named in and the only one in the library, and
 * `ErrorClassifier`, the single place it is assigned. The retry policy, the ladder and the token
 * refresh that read it are the issues after #177.
 */
package com.superplayer.resilience
