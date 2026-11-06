export function createUserScopeGuard() {
  let generation = 0;
  let activeTicket = null;

  return {
    begin(scopeId) {
      activeTicket = {
        generation: ++generation,
        scopeId: String(scopeId)
      };
      return activeTicket;
    },
    invalidate() {
      generation += 1;
      activeTicket = null;
    },
    isCurrent(ticket, currentScopeId) {
      return Boolean(ticket
        && ticket === activeTicket
        && ticket.generation === generation
        && ticket.scopeId === String(currentScopeId ?? ''));
    }
  };
}
