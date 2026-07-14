# Prismo Development Guide

> This document describes **how Prismo is developed**, not what Prismo is.
>
> The product specification, architecture, roadmap, and feature set are documented in `PROJECT.md`.
>
> This file serves as the development handbook for myself, future contributors, and any AI assistants used during development.

---

# Current Repository State

The repository is in its initial stage.

Current structure:

```
Prismo/
│
├── docs/
│   ├── PROJECT.md
│   └── DEVELOPMENT.md
│
├── README.md
├── LICENSE
└── .gitignore
```

No application code exists yet.

There is currently:

- No Gradle project
- No JavaFX setup
- No package structure
- No source code
- No SQLite database
- No configuration files

Development begins from this point.

---

# Source of Truth

The primary project specification is:

```
docs/PROJECT.md
```

This document defines:

- Vision
- Features
- Architecture
- Database philosophy
- Roadmap
- Technical decisions

Unless implementation proves something unrealistic, PROJECT.md should remain the authoritative source.

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

---

# Milestones

## v0.1 Foundation

- Project setup
- Database
- Media Indexer

Goal:

Establish a stable engineering foundation.

---

## v0.5 Beta

- Thumbnail Cache
- Timeline
- Search
- Filesystem Monitoring

Goal:

Feature-complete core application.

---

## v1.0 Stable

- Viewer
- Collections
- Packaging
- Polish

Goal:

First stable public release.

---

# Implementation Rules

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

- Oracle documentation
- OpenJFX documentation
- Gradle documentation
- SQLite documentation

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

Major architectural changes should be reflected in PROJECT.md.

Development process changes should be reflected in DEVELOPMENT.md.

---

# Final Goal

Prismo should be a polished, offline-first desktop media library that demonstrates strong software engineering practices.

Equally important, the developer should fully understand the project, its architecture, and every significant implementation decision.

This repository should reflect not only the finished application, but also a disciplined and professional development process.