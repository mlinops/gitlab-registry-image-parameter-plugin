/**
 * Lazy-load GitLab registry tags on select focus / first open.
 * Server loads parameter config by name (no credentials/repo in the request body).
 */
(function () {
  if (window.__griLazyTagsBound) {
    return;
  }
  window.__griLazyTagsBound = true;

  function fillSelect(select, tags, keepValue) {
    var previous = keepValue || select.value;
    select.innerHTML = '';
    for (var i = 0; i < tags.length; i++) {
      var t = tags[i];
      if (t == null || t === '') {
        continue;
      }
      var opt = document.createElement('option');
      opt.value = t;
      opt.textContent = t;
      select.appendChild(opt);
    }
    if (previous) {
      select.value = previous;
      if (select.value !== previous) {
        var extra = document.createElement('option');
        extra.value = previous;
        extra.textContent = previous;
        select.insertBefore(extra, select.firstChild);
        select.value = previous;
      }
    } else if (select.options.length > 0) {
      select.selectedIndex = 0;
    }
  }

  function fillError(select, message) {
    select.innerHTML = '';
    var opt = document.createElement('option');
    opt.value = '';
    opt.textContent = message || 'ERROR';
    opt.disabled = true;
    opt.selected = true;
    select.appendChild(opt);
  }

  /** Strip a leading "//" so the path stays same-origin. */
  function normalizeJenkinsPath(url) {
    if (!url) {
      return '';
    }
    if (url.indexOf('//') === 0) {
      return url.substring(1);
    }
    return url;
  }

  function buildFromLocation(select) {
    var descId = select.getAttribute('data-descriptor-id');
    if (!descId) {
      return '';
    }
    // Build page: /…/job/…/build → /…/job/…/descriptorByName/<id>/fetchTags
    var path = window.location.pathname || '';
    var stripped = path.replace(/\/(buildWithParameters|build)\/?$/i, '/');
    if (stripped === path || stripped.indexOf('/job/') < 0) {
      return '';
    }
    return stripped + 'descriptorByName/' + encodeURIComponent(descId) + '/fetchTags';
  }

  function resolveFetchUrl(select) {
    var fromPage = buildFromLocation(select);
    if (fromPage) {
      return fromPage;
    }
    return normalizeJenkinsPath(select.getAttribute('data-fetch-url'));
  }

  function loadTags(select) {
    if (select.getAttribute('data-gri-loaded') === 'true'
        || select.getAttribute('data-gri-loading') === 'true') {
      return;
    }
    var url = resolveFetchUrl(select);
    if (!url) {
      fillError(select, 'ERROR: no job context for tag fetch');
      return;
    }

    var paramName = select.getAttribute('data-param-name') || '';
    if (!paramName) {
      fillError(select, 'ERROR: missing parameter name');
      return;
    }

    select.setAttribute('data-gri-loading', 'true');
    var loading = document.createElement('option');
    loading.value = select.value || '';
    loading.textContent = 'Loading…';
    loading.selected = true;
    select.appendChild(loading);

    var body = new URLSearchParams();
    body.set('name', paramName);

    var defaultVersion = select.getAttribute('data-default-version') || '';
    var keep = defaultVersion || select.value;

    fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: crumb.wrap({
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
      }),
      body: body.toString()
    })
      .then(function (res) {
        if (!res.ok) {
          throw new Error('HTTP ' + res.status);
        }
        return res.json();
      })
      .then(function (data) {
        select.removeAttribute('data-gri-loading');
        if (!data || data.ok === false) {
          var err = (data && data.error) ? data.error : 'fetch failed';
          fillError(select, 'ERROR: ' + err);
          select.removeAttribute('data-gri-loaded');
          return;
        }
        fillSelect(select, data.tags || [], keep);
        select.setAttribute('data-gri-loaded', 'true');
      })
      .catch(function (err) {
        select.removeAttribute('data-gri-loading');
        fillError(
          select,
          'ERROR: ' + (err && err.message ? err.message : 'network')
        );
      });
  }

  function onActivate(e) {
    var select = e.target && e.target.closest
      ? e.target.closest('select[data-gri-lazy="true"]')
      : null;
    if (select) {
      loadTags(select);
    }
  }

  document.addEventListener('focusin', onActivate, true);
  document.addEventListener('mousedown', onActivate, true);
})();
