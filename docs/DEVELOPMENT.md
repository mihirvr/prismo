# Prismo Development Guide

> This document describes **how Prismo is developed**, not what Prismo is.
>
> The product specification, architecture, roadmap, and feature set are documented in `PROJECT.md`.
>
> This file serves as the development handbook for myself, future contributors, and any AI assistants used during development.

---

# Current Repository State

Issues #1–#6 are done: a Gradle project (Java 25, JavaFX 25), the SQLite database layer with migrations, the media indexer, the thumbnail cache, the Timeline UI, and the media viewer. The app runs with `./gradlew run` and tests run with `./gradlew test`.

The current file layout is the "Project Structure" section of `README.md`.

Next on the roadmap: Search (#7).

---

# Source of Truth

The project is specified by two documents:

```
docs/PROJECT.md      — what Prismo is
docs/TECH_STACK.md   — how it is built
```

PROJECT.md defines:

- Vision
- Features
- Architecture overview
- Database philosophy
- Roadmap

TECH_STACK.md defines:

- Libraries and versions
- Java / JavaFX version
- Threading and database write model
- Packaging approach

Unless implementation proves something unrealistic, these two documents remain the authoritative source. Items marked **(verify in #N)** in TECH_STACK.md must be checked in that issue before code depends on them.

Any architectural changes should be discussed and documented rather than silently introduced.

---

# Project Philosophy

Prismo is intended to be built like a real software project rather than an AI-generated application.

Goals:

- Understand every architectural decision.
- Write maintainable code.
- Keep modules small.
- Avoid unnecessary complexity.
- Build incrementally.
- Prioritize correctness over speed.

Understanding the codebase is more important than finishing quickly.

---

# Development Workflow

Development follows this cycle:

Study

↓

Plan

↓

Implement

↓

Test

↓

Review

↓

Commit

↓

Push

↓

Close Issue

↓

Repeat

Every feature should be fully understood before moving to the next.

---

# GitHub Workflow

Development is managed using GitHub.

The repository contains:

- Issues
- Milestones
- Project Board

The Project Board workflow is:

Todo

↓

In Progress

↓

Done

Every completed task should move through these stages.

## Cross-Platform CI/CD Awareness

Because `jpackage` cannot cross-compile (e.g., Windows cannot build a `.deb`), final testing for Issue #10 (Packaging) will eventually require setting up GitHub Actions to spin up OS-specific runners. The Windows runner also needs the WiX Toolset to build `.msi`/`.exe`.

---

# Current GitHub Issues

Implementation order is tracked using GitHub Issues.

Current roadmap:

1. Project Setup
2. Database Layer
3. Media Indexer
4. Thumbnail Cache
5. Timeline UI
6. Media Viewer
7. Search
8. Filesystem Watcher
9. Collections
10. Packaging

Unless implementation experience suggests otherwise, development should follow this order.

Every issue should be assigned to its milestone on GitHub.

---

# Milestones

These match the scope tiers in PROJECT.md.

## v0.1 Foundation

- Project Setup (#1)
- Database Layer (#2)
- Media Indexer (#3)

Goal:

Establish a stable engineering foundation.

---

## v0.5 Core Loop

- Thumbnail Cache (#4)
- Timeline UI (#5)
- Media Viewer (#6)
- Search (#7)
- Filesystem Watcher (#8)

Goal:

The core loop (index → thumbnails → browse → view → search → live updates) works and feels fast.

---

## v1.0 MVP

- Collections (#9)
- Packaging (#10)
- The baseline gallery features listed under v1.0 in PROJECT.md (Favorites, Hidden, Trash, Filters, Sort, Metadata Panel, Selection Mode, File Operations, Multiple Locations, Broken & Unavailable Media, HEIC, RAW previews, Video Watch Progress). These still need GitHub issues.
- Polish

Goal:

First stable public release.

---

# Implementation Rules

## The Dependency Rule

Prismo is a classpath application with **no `module-info.java`**. `jlink` builds only the Java runtime (JDK + JavaFX modules), and `jpackage` adds the app jars on top. The reason: several libraries we need (metadata-extractor, TwelveMonkeys) are not real Java modules, and `jlink` rejects those. Full explanation in TECH_STACK.md.

When adding a library:

1. Add its version to `gradle/libs.versions.toml` and reference it from `build.gradle.kts`. No versions written directly in the build file.
2. Record it in TECH_STACK.md (what it's for, its license).
3. Re-run `jdeps --print-module-deps --ignore-missing-deps` on the app jars. If it now needs a JDK module that the `jlink` module list doesn't include, add it there. Otherwise the packaged app fails at runtime even though `./gradlew run` works.

## Threading Rule

- No blocking work on the JavaFX Application Thread: no file I/O, no SQL, no image decoding.
- All SQLite writes go through the single DB writer. Nothing else writes to the database.

---

# Issue Workflow

Every GitHub Issue should follow the same workflow.

## 1. Objective

Understand:

- What is being built.
- Why it exists.
- How it fits into the architecture.

---

## 2. Study

Before coding, study only what is necessary.

Preference should always be given to:

- Oracle documentation and OpenJDK JEPs
- OpenJFX documentation
- Gradle documentation
- SQLite documentation
- Each library's own README and Javadoc

Avoid relying solely on tutorials.

Understand the APIs before using them.

---

## 3. Planning

Before implementation:

- break work into small steps
- identify dependencies
- identify edge cases

Large implementations should be avoided.

---

## 4. Implementation

Implement one step at a time.

Every step should:

- compile
- be testable
- follow the architecture
- remain maintainable

Avoid generating large amounts of code in a single iteration.

---

## 5. Testing

Before committing:

- verify compilation
- verify functionality
- verify edge cases where applicable

Do not assume code works.

Test it.

---

## 6. Review

Review:

- package structure
- naming
- architecture
- performance
- maintainability

If something feels wrong, refactor before continuing.

---

## 7. Git

Every completed step should be committed.

Commit messages should be meaningful.

Examples:

```
Initialize Gradle project

Configure JavaFX

Implement SQLite connection

Create media schema

Implement recursive indexer

Add thumbnail cache
```

Avoid commits like:

```
fix

update

changes

asdf
```

---

# AI Assistant Guidelines

AI is used as:

- mentor
- reviewer
- debugger
- documentation assistant

AI is **not** the primary developer.

Responsibilities:

- explain concepts
- review architecture
- review code
- identify problems
- suggest improvements
- recommend documentation

AI should not:

- generate the complete application
- skip roadmap stages
- rewrite architecture without discussion
- introduce features outside PROJECT.md
- add libraries outside TECH_STACK.md without discussion

The human developer owns all architectural decisions.

---

# Learning First

For every module:

Understand first.

Then implement.

Whenever possible:

- read official documentation
- understand the API
- understand why a design exists

The objective is not simply to finish the project.

The objective is to become capable of explaining every implementation decision confidently.

---

# Code Quality

## Strict Rules

- **Path Resolution & Hardcoding**: Explicitly forbidden to hardcode file paths (like `C:\` or `~`). All file system interactions and cache/database locations must route through a dedicated OS-aware path utility class to ensure cross-platform compatibility.
- **Logging Over Stdout**: The use of `System.out.println()` is strictly forbidden. All debugging and application logging must use the SLF4J logger to ensure errors are captured in the local rotating log files on the user's disk.

Prefer:

- readable code
- modular design
- meaningful names
- composition over unnecessary inheritance
- production-quality practices

Avoid:

- premature optimization
- unnecessary abstractions
- copy-pasting code without understanding it

---

# Documentation

Keep documentation updated as the project evolves.

Major architectural or feature changes should be reflected in PROJECT.md.

Library, version and implementation-approach changes should be reflected in TECH_STACK.md.

Development process changes should be reflected in DEVELOPMENT.md.

---

# Final Goal

Prismo should be a polished, offline-first desktop media library that demonstrates strong software engineering practices.

Equally important, the developer should fully understand the project, its architecture, and every significant implementation decision.

This repository should reflect not only the finished application, but also a disciplined and professional development process.