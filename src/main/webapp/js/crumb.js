/**
 * Shared Jenkins crumb helper for plugin XHR.
 */
window.griCrumbHeaders = window.griCrumbHeaders || function (base) {
  var headers = base || {};
  try {
    if (typeof crumb !== 'undefined' && crumb && typeof crumb.wrap === 'function') {
      return crumb.wrap(headers) || headers;
    }
  } catch (e) { /* ignore */ }
  var field = document.querySelector('meta[name="crumbRequestField"]');
  var crumbMeta = document.querySelector('meta[name="crumb"]');
  if (field && crumbMeta) {
    headers[field.getAttribute('content')] = crumbMeta.getAttribute('content');
  }
  return headers;
};
