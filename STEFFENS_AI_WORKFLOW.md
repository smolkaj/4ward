# Steffen's AI Workflow

NOTE: AI is a rapidly evolving field, and I am still learning.

I use Claude Code from the terminal.
```
claude --dangerously-skip-permissions
```

## Principles

*Principle*: Ask, don't tell.
*Justification*:
- Telling the AI what to do is far too much work. Let the AI do the thinking!
  Help it course correct by asking questions.
*Takeaway*: Don't micromanage! Treat the AI like a trusted L5, not like an L3.

*Principle*: Don't provide context — let the AI build its own.
*Justification*:
- Explaining is tedious and slow.
- The AI is much faster at reading and synthesizing than you are at explaining.
- The AI will build a much deeper understanding through self-exploration (much 
  like a human, but WAY faster!)
*Takeaway*: Ask questions that make the AI gather and reconstruct context itself.
Your job is to steer its attention, not transfer your understanding.

## After submitting a PR
- What's next?
  - *Leverages the agents valuable context instead of discarding it!*
- Let's zoom out and talk big picture. Where are we? Where are we going?
  — *Forces the AI to reconstruct the big picture from the codebase itself.*

## Reviewing the AIs code
- In hindsight, anything you'd refactor?
  — *Leverages the context the AI already built during implementation to self-critique.*
- Do we need to add unit tests?
- Do we need to update documentation?
- /simplify

## Letting the AI do Code Reviews
- Review this PR
- Do you understand the intent of this PR?
  — *Makes the AI prove it has the right context before you trust its review.*
- Is this the most optimal way to do it?
- What would be a better way?
- Have you looked into this part, this part, this part (point to relevant parts)
  — *You're directing attention, not explaining.*
- Could we do even better if we did a refactor?


## Resources
- Learn from the best! I highly recommend this podcast with Peter Steinberg, the
  creator of OpenClaw: https://www.youtube.com/watch?v=YFjfBk8HI5o
    - OpenClaw was created entirely using AI prompting and gained 100,000+
      GitHub stars in < 1 week -- the fastest growing project ever!
