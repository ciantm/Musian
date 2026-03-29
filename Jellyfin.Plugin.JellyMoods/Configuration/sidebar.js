/**
 * JellyMoods sidebar injector — loaded via config.json plugins array.
 * Injects a "JellyMoods" nav item into libraryMenuOptions after the Music entry.
 */
define([], function () {
  'use strict';

  var ITEM_ID  = 'jellymoods-nav-item';
  var APP_URL  = '/JellyMoods/app';

  function buildItem() {
    var a = document.createElement('a');
    a.id        = ITEM_ID;
    a.href      = APP_URL;
    a.className = 'navMenuOption lnkMediaFolder';
    a.innerHTML =
      '<span class="material-icons navMenuOptionIcon music_note" aria-hidden="true"></span>' +
      '<span class="navMenuOptionText">JellyMoods</span>';
    a.addEventListener('click', function (e) {
      e.preventDefault();
      window.location.href = APP_URL;
    });
    return a;
  }

  function findMusicItem(container) {
    // Match the icon span that has "music_note" in its class
    var icons = container.querySelectorAll('.navMenuOptionIcon');
    for (var i = 0; i < icons.length; i++) {
      if (/\bmusic_note\b/.test(icons[i].className)) return icons[i].closest('a');
    }
    // Fallback: match by text
    var links = container.querySelectorAll('a');
    for (var j = 0; j < links.length; j++) {
      var txt = links[j].querySelector('.navMenuOptionText');
      if (txt && txt.textContent.trim().toLowerCase() === 'music') return links[j];
    }
    return null;
  }

  function inject() {
    var container = document.querySelector('.libraryMenuOptions');
    if (!container) return;

    var musicEl = findMusicItem(container);
    if (!musicEl) return; // Music not rendered yet — wait for next mutation

    var existing = document.getElementById(ITEM_ID);
    if (existing && existing.previousElementSibling === musicEl) return; // already correct

    if (existing) existing.parentNode.removeChild(existing);
    musicEl.parentNode.insertBefore(buildItem(), musicEl.nextSibling);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', inject);
  } else {
    inject();
  }

  if (!window._jmObserver) {
    window._jmObserver = new MutationObserver(inject);
    window._jmObserver.observe(document.body, { childList: true, subtree: true });
  }

  return {};
});
