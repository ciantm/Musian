/**
 * JellyMoods sidebar injector — loaded via config.json plugins array.
 * Adds "JellyMoods" to the main nav after the Music entry.
 */
(function () {
  'use strict';

  var ITEM_ID = 'jellymoods-nav-item';
  var APP_URL = '/JellyMoods/app';

  function buildItem() {
    var a = document.createElement('a');
    a.id = ITEM_ID;
    a.href = APP_URL;
    a.style.cssText = 'display:flex;align-items:center;padding:8px 20px 8px 18px;cursor:pointer;text-decoration:none;color:inherit;';
    a.innerHTML =
      '<span style="margin-right:14px;font-size:1.4em;">🎵</span>' +
      '<span>JellyMoods</span>';
    a.addEventListener('click', function (e) {
      e.preventDefault();
      window.location.href = APP_URL;
    });
    return a;
  }

  function findMusicItem(root) {
    // Try data-itemid first (older Jellyfin)
    var byId = root.querySelector('[data-itemid="music"]');
    if (byId) return byId;
    // Try href containing music routes
    var links = root.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute('href') || '';
      if (/[#/]music/i.test(href)) return links[i];
    }
    // Fall back to text match
    var all = root.querySelectorAll('a, li, [role="menuitem"]');
    for (var j = 0; j < all.length; j++) {
      if (all[j].textContent.trim().toLowerCase() === 'music') return all[j];
    }
    return null;
  }

  function findNav() {
    var selectors = [
      '.navMenuOptions',
      '.mainDrawer-scrollContainer',
      '.mainDrawer nav',
      'nav[class*="drawer"]',
      '[class*="navDrawer"]',
      'nav',
    ];
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) return el;
    }
    return null;
  }

  function inject() {
    if (document.getElementById(ITEM_ID)) return;
    var nav = findNav();
    if (!nav) return;
    var item = buildItem();
    var musicEl = findMusicItem(nav);
    if (musicEl) {
      var target = musicEl.closest('li') || musicEl;
      target.parentNode.insertBefore(item, target.nextSibling);
    } else {
      nav.appendChild(item);
    }
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
})();
