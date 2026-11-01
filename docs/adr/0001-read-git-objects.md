# ADR-0001: Read committed git objects, not the filesystem

**Status:** Accepted

## Context
The tools must read files the model names, and that name is untrusted. Reading from disk would mean defending against path traversal and symlinks, and would expose anything in the working tree, including untracked files such as `.env`.

## Decision
File tools read through git (`git cat-file blob ref:path`, `git grep ref`, `git ls-tree ref`). They always name a revision and never open a working-tree file.

## Consequences
- Untracked and ignored files are out of reach, and a symlink is returned as its target text rather than followed.
- The model can read any revision the same way, which suits questions about history.
- Uncommitted changes are invisible, so the agent cannot explain work in progress.
- Secrets that were committed can still be read.
