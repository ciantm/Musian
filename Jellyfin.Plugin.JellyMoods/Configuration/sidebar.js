/**
 * JellyMoods sidebar injector — loaded globally via config.json.
 * Adds "JellyMoods" to the main nav under Music for all users.
 * Points to /JellyMoods/app — standalone page outside the dashboard shell.
 */
(function () {
  'use strict';

  var ITEM_ID = 'jellymoods-nav-item';
  var APP_URL = '/JellyMoods/app';

  function buildItem() {
    var a = document.createElement('a');
    a.id        = ITEM_ID;
    a.href      = APP_URL;
    a.className = 'navMenuOption flex align-items-center';
    a.setAttribute('data-itemid', 'jellymoods');
    a.style.cssText = 'display:flex;align-items:center;padding:8px 20px 8px 18px;cursor:pointer;text-decoration:none;color:inherit;';
    a.innerHTML =
      '<span class="material-icons navMenuOptionIcon" style="margin-right:14px;font-size:1.4em;">music_note</span>' +
      '<span class="navMenuOptionText">JellyMoods</span>';
    a.addEventListener('click', function (e) {
      e.preventDefault();
      window.location.href = APP_URL;
    });
    return a;
  }

  function inject() {
    if (document.getElementById(ITEM_ID)) return;
    var nav =
      document.querySelector('.navMenuOptions') ||
      document.querySelector('.mainDrawer-scrollContainer .navMenuOptions') ||
      document.querySelector('.mainDrawer .navMenuOptions');
    if (!nav) return;
    var musicLink = Array.from(nav.querySelectorAll('a,[data-itemid]'))
      .find(function (el) {
        return (el.getAttribute('data-itemid') || '').toLowerCase() === 'music' ||
               el.textContent.trim().toLowerCase() === 'music';
      });
    var item = buildItem();
    if (musicLink) {
      var target = musicLink.closest('li') || musicLink;
      target.parentNode.insertBefore(item, target.nextSibling);
    } else {
      nav.appendChild(item);
    }
  }

  inject();

  if (!window._jmObserver) {
    window._jmObserver = new MutationObserver(function () { inject(); });
    window._jmObserver.observe(document.body, { childList: true, subtree: true });
  }
})();
