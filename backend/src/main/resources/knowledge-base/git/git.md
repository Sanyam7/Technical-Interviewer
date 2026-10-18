---
title: Git version control
technology: git
doc_type: REFERENCE
---

## Commits, the object model and branches

Git stores snapshots, not diffs: a commit points to a tree (the directory snapshot), its parent commits, the author and
the message, and is identified by the hash of its content. History is a directed acyclic graph of commits. A branch is
a movable pointer to a commit, and HEAD points to the current branch. Creating a branch is cheap because it is only a
reference. The staging area (index) holds what the next commit will contain.

## Merge versus rebase

Merging combines two branches with a merge commit that has two parents (or a fast-forward when one branch is ahead),
preserving the true history. Rebasing replays a branch's commits on top of another base, creating new commits with new
hashes and a linear history. Never rebase commits others have already pulled, because rewritten history forces them to
reconcile duplicates; rebasing local work before sharing it is fine. Squash merging collapses a branch into one commit.
Conflicts arise when both sides changed the same lines; resolving them means editing the file, staging and continuing.

## Undoing changes

`git revert` creates a new commit that undoes an earlier one and is safe on shared branches. `git reset` moves the
branch pointer: `--soft` keeps changes staged, `--mixed` (default) keeps them in the working tree, and `--hard`
discards them. `git restore` discards working-tree changes to a file. `git reflog` records where HEAD has been, so
commits lost by a reset or rebase can usually be recovered. Force-pushing rewritten history to a shared branch can
destroy colleagues' work; `--force-with-lease` refuses if the remote moved.

## Finding problems in history

`git bisect` binary-searches the history between a known good and a known bad commit to find the commit that
introduced a bug in O(log n) steps, and can run a test script automatically. `git blame` shows the last commit that
changed each line, and `git log -S` finds commits that added or removed a string.

## Workflows

Trunk-based development keeps short-lived branches merged into main frequently, with feature flags hiding unfinished
work, and pairs well with continuous integration. Git Flow uses long-lived develop and release branches and suits
scheduled releases. Pull requests add code review and automated checks before merging. Small, focused commits with
clear messages make review, revert and bisect easier. Never commit secrets; removing them later requires rewriting
history and rotating the secret.
