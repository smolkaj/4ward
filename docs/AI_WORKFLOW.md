# AI Workflow

> **Personal field notes from [@smolkaj](https://github.com/smolkaj).**
> AI-assisted development is evolving fast and so is this document — treat it
> as practitioner notes, not project doctrine.

I use Claude Code from the terminal.
```
claude --dangerously-skip-permissions
```

## Learn from the best

I highly recommend [this podcast](https://www.youtube.com/watch?v=YFjfBk8HI5o) featuring
Peter Steinberg, the creator of OpenClaw. OpenClaw is the most viral open source project
of all time, going from 0 to 100k GitHub stars in 1 week. It was entirely written by AI.

In the podcast, Peter shares his insights and specifics about his workflow. I highly recommend
you listen and learn from the best.

## Principles

**Principle**: Ask, don't tell.

**Justification**: Telling the AI what to do is far too much work. Let the AI do the thinking!
  Help it course correct by asking questions.

**Takeaway**: Don't micromanage! Treat the AI like a trusted L5, not like an L3.

---

**Principle**: Don't provide context — let the AI build its own.

**Justification**:
- Explaining is tedious and slow.
- AI is much faster at reading and synthesizing than you are at explaining.
- AI will build a deeper understanding through self-exploration (much 
  like a human, but WAY faster!)

**Takeaway**:
- Ask questions that make the AI gather and reconstruct context itself.
- Your job is to steer its attention, not transfer your understanding.

---

**Principle**: Don't pick the next task — ask "What's next?"

**Justification**:
- The AI just finished a task and has deep context on the codebase, recent
  changes, and project momentum. That context is valuable — don't discard it
  by assigning a random task.
- The AI may see follow-up work, regressions, or opportunities you haven't
  noticed yet.
- Letting the AI propose next steps keeps it in the driver's seat, which is
  where it's most productive.

**Takeaway**: After a task completes, ask "What's next?" instead of dictating
the next move. Steer the answer if needed, but let the AI lead.

---

**Principle**: When presented with options, ask: "wdyt?"

**Justification**:
- Less typing! ;)
- It makes the AI reflect and build its own understanding of what the best option is.
  This context will be immensly valuable in executing the option.

---

## Before submitting a PR
- "In hindsight, is this the most optimal way of doing things?" / "In hindsight, anything you'd refactor?"
  - *Leverages the context the AI already built during implementation to self-critique.*
- "Do we need to add unit tests?"
  - *The AI knows better than you, unless you are willing to read all its code!*
- "Do we need to update documentation?"
  - *The AI knows better than you, unless you are willing to read all its code!*
- "/simplify"
- Most of the time, I don't try to understand the AIs code. Most of the time, I don't even read it.
- "Check CI and merge once it passes."


## After submitting a PR
- "What's next?"
  - *Leverages the agents valuable context instead of discarding it!*
- "Let's zoom out and talk big picture. Where are we? Where are we going?"
  — *Forces the AI to reconstruct the big picture from the codebase itself.*


## Letting the AI review other's code
- "Review this PR"
- "Do you understand the intent of this PR?"
  — *Makes the AI prove it has the right context before you trust its review.*
- "Is this the most optimal way to do it?"
- "What would be a better way?"
- "Have you looked into this part, this part, this part" (point to relevant parts)
  — *You're directing attention, not explaining.*
- "Could we do even better if we did a refactor?"

## My 2c

I don't like planning mode. Instead, I prefer to just have a conversation with the
agent. To prevent the agent from going into planning mode, you can add the following
to your `~/.claude/settings.json`:
```
  "permissions": {
    "deny": [
      "EnterPlanMode"
    ]
  }
```


## Resources
- Learn from the best! I highly recommend this podcast with Peter Steinberg, the
  creator of OpenClaw: https://www.youtube.com/watch?v=YFjfBk8HI5o
    - OpenClaw was created entirely using AI prompting and gained 100,000+
      GitHub stars in < 1 week -- the fastest growing project ever!
