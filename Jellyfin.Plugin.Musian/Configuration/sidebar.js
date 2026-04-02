/**
 * Musian sidebar injector — loaded via config.json plugins array.
 * Injects a "Musian" nav item into libraryMenuOptions after the Music entry.
 */
define([], function () {
  'use strict';

  var ITEM_ID  = 'musian-nav-item';
  var APP_URL  = '/Musian/app';
  var _timer   = null;

  function buildItem() {
    var a = document.createElement('a');
    a.id        = ITEM_ID;
    a.href      = APP_URL;
    a.setAttribute('is', 'emby-linkbutton');
    a.className = 'lnkMediaFolder navMenuOption';
    a.innerHTML =
      '<span class="material-icons navMenuOptionIcon music_note" aria-hidden="true"></span>' +
      '<span class="navMenuOptionText">Musian</span>';
    a.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      window.location.href = APP_URL;
    });
    return a;
  }

  function findMusicItem(container) {
    var links = container.querySelectorAll('a');
    for (var i = 0; i < links.length; i++) {
      // Match by music_note icon class
      var icon = links[i].querySelector('.navMenuOptionIcon');
      if (icon && /\bmusic_note\b/.test(icon.className)) return links[i];
    }
    // Fallback: match any text containing "music"
    for (var j = 0; j < links.length; j++) {
      var txt = links[j].querySelector('[class*="navMenuOptionText"]');
      if (txt && txt.textContent.trim().toLowerCase() === 'music') return links[j];
    }
    return null;
  }

  function inject() {
    var container = document.querySelector('.libraryMenuOptions');
    if (!container) return false;

    var musicEl = findMusicItem(container);
    if (!musicEl) return false; // not ready yet

    var existing = document.getElementById(ITEM_ID);
    if (existing && existing.previousElementSibling === musicEl) return true; // already correct

    if (existing) existing.parentNode.removeChild(existing);
    musicEl.parentNode.insertBefore(buildItem(), musicEl.nextSibling);
    return true;
  }

  function poll() {
    if (inject()) return; // done
    _timer = setTimeout(poll, 300);
  }

  // Kick off polling
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', poll);
  } else {
    poll();
  }

  // Also watch DOM mutations (nav re-renders on SPA navigation)
  if (!window._jmObserver) {
    window._jmObserver = new MutationObserver(function () {
      clearTimeout(_timer);
      poll();
    });
    window._jmObserver.observe(document.body, { childList: true, subtree: true });
  }

  return {};
});
