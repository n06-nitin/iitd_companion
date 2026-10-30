import Foundation

/// JavaScript injected into the RollCall site. It behaves like a password
/// manager + bookmarklet: fills the login fields, clicks the site's own
/// buttons, and reports the current page + course list back to the app.
/// It never reads or submits the CAPTCHA — the human does that.
///
/// Selectors come from the site's real markup:
///   landing: <button ... onclick="location.href='.../authorize.php?...'">Login via IITD OAuth</button>
///   eAcademics landing: <a href="https://oauth.iitd.ac.in/authorize.php?...">Click here to login</a>
///   login:   <input name="username">, <input name="password">, <input name="ct_captcha" id="captcha_code">
enum RollCallScript {

    static let bootstrap = #"""
(function(){
  if (window.__RCH_INSTALLED) { try { window.__RCH.report(); } catch(e){} return; }
  window.__RCH_INSTALLED = true;

  function post(o){ try { window.webkit.messageHandlers.ic.postMessage(JSON.stringify(o)); } catch(e){} }
  function txt(el){ return ((el.innerText||el.textContent||el.value||'')+'').trim(); }
  function q(sel){ try { return document.querySelector(sel); } catch(e){ return null; } }
  function clickables(){ return [].slice.call(document.querySelectorAll('a,button,input[type=submit],input[type=button],[role=button],[onclick]')); }
  function pick(){ for (var i=0;i<arguments.length;i++){ var el=q(arguments[i]); if(el) return el; } return null; }
  function byText(t){
    t=(t||'').trim().toLowerCase();
    var els=clickables(), i, e;
    for (i=0;i<els.length;i++){ if(txt(els[i]).toLowerCase()===t) return els[i]; }
    for (i=0;i<els.length;i++){ e=txt(els[i]).toLowerCase(); if(e && e.indexOf(t)!==-1) return els[i]; }
    return null;
  }
  function click(el){ if(el){ try{el.click();}catch(e){} return true; } return false; }

  // the site's course buttons ("MCL261", "MTL2006 (Sec B)", "MEL2002-Lab", "PYL1001LH408");
  // pages without them (the demo) are matched by a leading course code
  var COURSE_RE=/^[A-Z]{2,4}[0-9]{3,4}/;
  function courseEls(){
    var els=[].slice.call(document.querySelectorAll('.course-btn'));
    return els.length ? els : [].slice.call(document.querySelectorAll('button.btn, a.btn, .btn, button, a, [role=button]'))
      .filter(function(e){ return COURSE_RE.test(txt(e)); });
  }
  function scrapeCourses(){
    var seen={}, out=[];
    courseEls().forEach(function(e){ var t=txt(e); if(t&&!seen[t]){ seen[t]=1; out.push(t);} });
    return out;
  }
  // "Selected Course: MTL2006 (Sec B) (2026-27, Sem I)"
  function selectedCourse(){
    var el=q('.course-btn.selected-course');
    if (el) return txt(el);
    var b=(document.body?document.body.innerText:'')||'';
    var m=b.match(/Selected Course:[ \t]*([^\n]+)/);
    return m ? m[1].replace(/\s*\(\d{4}-\d{2},[^)]*\)\s*$/,'').trim() || null : null;
  }
  function isLogin(){
    return location.hostname.indexOf('oauth')!==-1
      || !!pick("#captcha_code","input[name='captcha']","img.captcha-image","input.captcha-field","input[type='password']");
  }
  function loginBtnEl(){
    return pick("button[onclick*='authorize.php']","button[onclick*='oauth.iitd.ac.in']","a[href*='oauth.iitd.ac.in/authorize.php']")
      || byText('Login via IITD OAuth');
  }
  function detect(){
    if (isLogin()) return 'login';
    if (/My Courses/i.test((document.body?document.body.innerText:'')||'') || scrapeCourses().length) return 'home';
    if (loginBtnEl()) return 'landing';
    return 'other';
  }
  function setVal(el,v){
    if(!el) return false;
    try { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set.call(el,v); }
    catch(e){ el.value=v; }
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
    return true;
  }

  window.__RCH={
    loginOAuth:function(){ return click(loginBtnEl()); },
    fillCreds:function(user,pass){
      var u=pick("input[name='username']","input#username","input[type='text']:not([name$='captcha'])","input:not([type])");
      var p=pick("input[name='password']","input[type='password']");
      var cap=pick("#captcha_code","input[name='ct_captcha']","input[name='captcha']","input.captcha-field");
      var okU=setVal(u,user), okP=setVal(p,pass);
      if (cap && cap!==u) cap.setAttribute('enterkeyhint','go');
      post({type:'filled', ok:(okU&&okP), captcha:!!cap});
      return okU&&okP;
    },
    selectCourse:function(code){
      var cands=[].slice.call(document.querySelectorAll('button.btn, a.btn, .btn, button, a, [role=button]'));
      for (var i=0;i<cands.length;i++){ if(txt(cands[i])===code){ cands[i].click(); return true; } }
      return false;
    },
    markAttendance:function(){ return click(byText('Mark Attendance')); },
    verify:function(){ return click(byText('Verify Attendance')); },
    logout:function(){ return click(byText('Logout')||byText('Log out')||byText('Sign out')); },
    report:function(){ post({type:'state', page:detect(), url:location.href, courses:scrapeCourses(), selected:selectedCourse()}); }
  };

  var last='', timer=0;
  function maybe(){
    var sig=detect()+'|'+scrapeCourses().join(',')+'|'+selectedCourse()+'|'+location.href;
    if(sig!==last){ last=sig; window.__RCH.report(); }
  }
  // light on battery: coalesce DOM bursts, and don't poll while the tab is hidden
  function soon(){ clearTimeout(timer); timer=setTimeout(maybe,250); }
  document.addEventListener('DOMContentLoaded', maybe);
  document.addEventListener('visibilitychange', function(){ if(!document.hidden) maybe(); });
  maybe();
  try { new MutationObserver(soon).observe(document.documentElement,{subtree:true,childList:true}); } catch(e){}
  setInterval(function(){ if(!document.hidden) maybe(); }, 1500);
})();
"""#

    /// Camera zoom for the QR scanner. The page shows its zoom slider (and pinch-to-zoom)
    /// only if the camera track reports a `zoom` capability, which Chrome does but
    /// WKWebView doesn't. For a camera without real zoom this reports one and zooms
    /// digitally: the preview is blown up, and the frames the scanner decodes are cropped
    /// to the same centre. A camera with real zoom is left alone. Runs at document start.
    static let zoom = #"""
(function(){
  if (window.__RCZ_INSTALLED) return;
  window.__RCZ_INSTALLED = true;
  var T = window.MediaStreamTrack && MediaStreamTrack.prototype;
  var C = window.CanvasRenderingContext2D && CanvasRenderingContext2D.prototype;
  if (!T || !T.getCapabilities || !C) return;

  var MAX = 4, zoom = new WeakMap(); // track -> digital zoom, only for cameras without real zoom
  var caps0 = T.getCapabilities, settings0 = T.getSettings, apply0 = T.applyConstraints, draw0 = C.drawImage;

  function digital(t){ if (t.kind !== 'video') return false; var c = caps0.call(t); return !(c && c.zoom); }
  function trackOf(v){ var s = v.srcObject; return s && s.getVideoTracks ? s.getVideoTracks()[0] : null; }
  function num(v){ return typeof v === 'number' ? v : v && (v.exact != null ? v.exact : v.ideal); }

  // the video shows what the scanner reads: its centre, blown up
  function paint(){
    [].forEach.call(document.querySelectorAll('video'), function(v){
      var t = trackOf(v), z = (t && zoom.get(t)) || 1;
      v.style.scale = z > 1 ? String(z) : '';
      v.style.clipPath = z > 1 ? 'inset(' + (50 - 50 / z) + '%)' : '';
    });
  }
  document.addEventListener('loadedmetadata', paint, true);

  T.getCapabilities = function(){
    var c = caps0.apply(this, arguments);
    return digital(this) ? Object.assign({}, c, { zoom: { min: 1, max: MAX, step: 0.1 } }) : c;
  };
  T.getSettings = function(){
    var s = settings0.apply(this, arguments);
    return digital(this) ? Object.assign({}, s, { zoom: zoom.get(this) || 1 }) : s;
  };
  T.applyConstraints = function(c){
    if (!c || !digital(this)) return apply0.apply(this, arguments);
    var z = num(c.zoom), rest = Object.assign({}, c);
    delete rest.zoom;
    if (Array.isArray(c.advanced)) {
      rest.advanced = c.advanced.map(function(set){
        if (set && set.zoom != null) { z = num(set.zoom); set = Object.assign({}, set); delete set.zoom; }
        return set;
      }).filter(function(set){ return set && Object.keys(set).length; });
      if (!rest.advanced.length) delete rest.advanced;
    }
    if (z == null) return apply0.apply(this, arguments);
    zoom.set(this, Math.min(Math.max(+z || 1, 1), MAX));
    paint();
    // an empty applyConstraints would drop the scanner's resolution constraints
    return Object.keys(rest).length ? apply0.call(this, rest) : Promise.resolve();
  };

  // the scanner copies each frame onto a canvas to decode it: crop that copy to the zoomed centre
  C.drawImage = function(src){
    var t = src instanceof HTMLVideoElement && trackOf(src), z = t && zoom.get(t);
    if (!(z > 1)) return draw0.apply(this, arguments);
    var a = arguments, W = src.videoWidth, H = src.videoHeight, s = [0, 0, W, H], d;
    if (a.length >= 9) { s = [a[1], a[2], a[3], a[4]]; d = [a[5], a[6], a[7], a[8]]; }
    else d = a.length >= 5 ? [a[1], a[2], a[3], a[4]] : [a[1], a[2], W, H];
    var cx = W / 2, cy = H / 2;
    return draw0.call(this, src, cx + (s[0] - cx) / z, cy + (s[1] - cy) / z, s[2] / z, s[3] / z, d[0], d[1], d[2], d[3]);
  };

  // more pixels to zoom into: ask for 1080p where the scanner only set a minimum width
  var md = navigator.mediaDevices, gum0 = md && md.getUserMedia;
  if (gum0) md.getUserMedia = function(c){
    var v = c && c.video;
    if (v && typeof v === 'object' && v.width && typeof v.width === 'object' && v.width.ideal == null && v.width.exact == null)
      c = Object.assign({}, c, { video: Object.assign({}, v, { width: Object.assign({}, v.width, { ideal: 1920 }) }) });
    return gum0.call(md, c);
  };
})();
"""#

    static func loginOAuth() -> String { "window.__RCH&&window.__RCH.loginOAuth();" }
    static func fillCreds(user: String, pass: String) -> String {
        "window.__RCH&&window.__RCH.fillCreds(\(jsQuote(user)),\(jsQuote(pass)));"
    }
    static func selectCourse(_ code: String) -> String { "window.__RCH&&window.__RCH.selectCourse(\(jsQuote(code)));" }
    static func markAttendance() -> String { "window.__RCH&&window.__RCH.markAttendance();" }
    static func verify() -> String { "window.__RCH&&window.__RCH.verify();" }
    static func logout() -> String { "window.__RCH&&window.__RCH.logout();" }
}
