/**
 * Config UI: Test connection (stable URL row) + status under the row.
 */
(function () {
  if (window.__griConfigBound) {
    return;
  }
  window.__griConfigBound = true;

  var FIELDS = [
    'repoUrl',
    'credentialsId',
    'skipSslVerification',
    'connectTimeoutMs',
    'readTimeoutMs'
  ];

  function crumbHeaders(base) {
    return (window.griCrumbHeaders || function (h) { return h || {}; })(base);
  }

  function findField(config, field) {
    var nodes = config.querySelectorAll('input, select, textarea');
    var i;
    var n;
    var el;
    for (i = 0; i < nodes.length; i++) {
      el = nodes[i];
      n = el.getAttribute('name') || '';
      if (n === field || n === '_.' + field || n.endsWith('.' + field) || n.endsWith('_.' + field)) {
        return el;
      }
    }
    if (field === 'credentialsId') {
      for (i = 0; i < nodes.length; i++) {
        el = nodes[i];
        n = el.getAttribute('name') || '';
        if (el.tagName === 'SELECT' && /credentialsId/i.test(n)) {
          return el;
        }
      }
    }
    return null;
  }

  function readParams(config) {
    var params = new URLSearchParams();
    FIELDS.forEach(function (field) {
      var el = findField(config, field);
      if (!el) {
        return;
      }
      if (el.type === 'checkbox') {
        params.set(field, el.checked ? 'true' : 'false');
      } else {
        params.set(field, el.value == null ? '' : String(el.value));
      }
    });
    return params;
  }

  function clearStatus(config) {
    var status = config.querySelector('.gri-test-connection-status');
    if (!status) {
      return;
    }
    status.innerHTML = '';
    status.classList.add('is-empty');
    status.classList.remove('validation-error-area--visible');
    status.style.height = '';
  }

  function revealStatus(status) {
    status.classList.remove('is-empty');
    status.classList.add('validation-error-area--visible');
    status.style.height = 'auto';
  }

  function showStatusFromValidation(status, html) {
    if (!status) {
      return;
    }
    var raw = html && String(html).trim() ? String(html) : '';
    if (!raw) {
      showStatusText(status, 'Empty response', 'error');
      return;
    }
    var kind = 'error';
    if (/\bok\b/i.test(raw) && /class\s*=\s*["'][^"']*\bok\b/i.test(raw)) {
      kind = 'ok';
    } else if (/class\s*=\s*["'][^"']*\bwarning\b/i.test(raw)) {
      kind = 'warning';
    } else if (/class\s*=\s*["'][^"']*\berror\b/i.test(raw)) {
      kind = 'error';
    } else if (/\bok\b/i.test(raw) && !/\berror\b/i.test(raw)) {
      kind = 'ok';
    }
    var text = raw.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    if (text.length > 240) {
      text = text.substring(0, 240) + '…';
    }
    showStatusText(status, text || 'Empty response', kind);
  }

  function showStatusText(status, text, cssClass) {
    if (!status) {
      return;
    }
    revealStatus(status);
    status.innerHTML = '';
    var div = document.createElement('div');
    div.className = cssClass || 'error';
    div.textContent = text || 'Error';
    status.appendChild(div);
  }

  function isConnectionField(el) {
    if (!el || !el.closest || !el.closest('.gri-config')) {
      return false;
    }
    var name = el.getAttribute('name') || '';
    if (/repoUrl|credentialsId|skipSslVerification|connectTimeoutMs|readTimeoutMs/i.test(name)) {
      return true;
    }
    return !!(el.closest('.gri-repo-url-row__input'));
  }

  function setBusy(button, busy) {
    button.disabled = !!busy;
    button.setAttribute('aria-busy', busy ? 'true' : 'false');
    // Fixed label text keeps the URL row width stable.
  }

  function runTest(button) {
    var config = button.closest('.gri-config');
    if (!config) {
      return;
    }
    var url = button.getAttribute('data-check-url');
    if (!url) {
      showStatusText(
        config.querySelector('.gri-test-connection-status'),
        'Test connection URL is missing',
        'error'
      );
      return;
    }
    var status = config.querySelector('.gri-test-connection-status');
    var progress = button.getAttribute('data-progress') || 'Testing…';
    var params = readParams(config);

    setBusy(button, true);
    showStatusText(status, progress, 'progress');

    fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: crumbHeaders({
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
      }),
      body: params.toString()
    })
      .then(function (res) {
        return res.text().then(function (text) {
          return { ok: res.ok, status: res.status, text: text };
        });
      })
      .then(function (rsp) {
        setBusy(button, false);
        if (!rsp.ok) {
          var brief = (rsp.text || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
          if (brief.length > 180) {
            brief = brief.substring(0, 180) + '…';
          }
          showStatusText(
            status,
            'HTTP ' + rsp.status + (brief ? ' — ' + brief : ''),
            'error'
          );
          return;
        }
        showStatusFromValidation(status, rsp.text);
      })
      .catch(function (err) {
        setBusy(button, false);
        showStatusText(
          status,
          err && err.message ? err.message : 'network error',
          'error'
        );
      });
  }

  document.addEventListener('click', function (e) {
    var btn = e.target && e.target.closest
      ? e.target.closest('button.gri-test-connection')
      : null;
    if (!btn) {
      return;
    }
    e.preventDefault();
    e.stopPropagation();
    runTest(btn);
  }, true);

  function onFieldEdit(e) {
    if (!isConnectionField(e.target)) {
      return;
    }
    var config = e.target.closest('.gri-config');
    if (config) {
      clearStatus(config);
    }
  }

  document.addEventListener('input', onFieldEdit, true);
  document.addEventListener('change', onFieldEdit, true);
})();
