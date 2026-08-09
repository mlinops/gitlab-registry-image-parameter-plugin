/** Clear stale f:validateButton status when Repo URL / Credentials change. */
(function () {
  if (window.__gitlabParamClearTestStatusBound) {
    return;
  }
  window.__gitlabParamClearTestStatusBound = true;

  function isConnectionField(el) {
    if (!el || !el.getAttribute) {
      return false;
    }
    var name = el.getAttribute('name') || '';
    if (/repoUrl$/i.test(name) || /(^|[._])repoUrl$/i.test(name)) {
      return true;
    }
    if (/(^|[._])credentialsId$/.test(name)) {
      return true;
    }
    return false;
  }

  function clearArea(area) {
    if (!area) {
      return;
    }
    area.innerHTML = '';
    area.classList.remove('validation-error-area--visible');
    area.style.height = '';
    area.style.display = '';
  }

  function clearValidateStatus(el) {
    var root = el.closest('.repeated-chunk');
    var containers;
    var i;
    if (!root) {
      // Fallback: nearest ancestor that owns a validate-button container (not the whole form).
      var p = el.parentElement;
      while (p && p.tagName !== 'FORM' && p !== document.body) {
        if (p.querySelector('.jenkins-validate-button__container')) {
          root = p;
          break;
        }
        p = p.parentElement;
      }
    }
    if (!root) {
      return;
    }
    containers = root.querySelectorAll('.jenkins-validate-button__container');
    for (i = 0; i < containers.length; i++) {
      clearArea(containers[i].querySelector('.validation-error-area'));
    }
  }

  function onEdit(e) {
    if (!isConnectionField(e.target)) {
      return;
    }
    clearValidateStatus(e.target);
  }

  document.addEventListener('input', onEdit, true);
  document.addEventListener('change', onEdit, true);
})();
