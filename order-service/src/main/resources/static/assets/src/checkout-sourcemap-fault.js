(function attachCheckoutSourcemapFault(global) {
  'use strict';

  function buildCheckoutDraft(actionName) {
    return {
      actionName: actionName,
      createdAt: new Date().toISOString(),
      payment: null,
    };
  }

  function applyCheckoutDiscount(draft) {
    const paymentTotal = draft.payment.total;
    return paymentTotal.toFixed(2);
  }

  function triggerCheckoutSourcemapFault(actionName) {
    const draft = buildCheckoutDraft(actionName);
    return applyCheckoutDiscount(draft);
  }

  global.SelfhealSourcemapFault = {
    triggerCheckoutSourcemapFault,
  };
}(window));
