package com.xlonlabs.iitdcompanion

import org.json.JSONObject

/**
 * JavaScript injected into the RollCall site. It behaves like a password
 * manager + bookmarklet: fills the login fields, clicks the site's own
 * buttons, and reports the current page + course list back to the app.
 * It never reads or submits the CAPTCHA — the human does that.
 *
 * Selectors come from the site's real markup:
 *   landing: <button ... onclick="location.href='.../authorize.php?...'">Login via IITD OAuth</button>
 *   login:   <input name="username">, <input name="password">, <input name="ct_captcha" id="captcha_code">
 */
object RollCallScript {

    const val BOOTSTRAP = """
(function(){
  if (window.__RCH_INSTALLED) { try { window.__RCH.report(); } catch(e){} return; }
  window.__RCH_INSTALLED = true;

  function post(o){ try { AndroidIC.postMessage(JSON.stringify(o)); } catch(e){} }
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

  var COURSE_RE=/^[A-Z]{2,4}[0-9]{3}[A-Z]?${'$'}/;
  function scrapeCourses(){
    var seen={}, out=[];
    [].slice.call(document.querySelectorAll('button.btn, a.btn, .btn, button, a, [role=button]'))
      .forEach(function(e){ var t=txt(e); if(COURSE_RE.test(t)&&!seen[t]){ seen[t]=1; out.push(t);} });
    return out;
  }
  function selectedCourse(){
    var b=(document.body?document.body.innerText:'')||'';
    var m=b.match(/Selected Course:\s*([A-Z]{2,4}[0-9]{3}[A-Z]?)/);
    return m?m[1]:null;
  }
  function isLogin(){
    return location.hostname.indexOf('oauth')!==-1
      || !!pick("#captcha_code","input[name='captcha']","img.captcha-image","input.captcha-field","input[type='password']");
  }
  function loginBtnEl(){
    return pick("button[onclick*='authorize.php']","button[onclick*='oauth.iitd.ac.in']") || byText('Login via IITD OAuth');
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

  var last='';
  function maybe(){
    var sig=detect()+'|'+scrapeCourses().join(',')+'|'+selectedCourse()+'|'+location.href;
    if(sig!==last){ last=sig; window.__RCH.report(); }
  }
  document.addEventListener('DOMContentLoaded', maybe);
  maybe();
  try { new MutationObserver(maybe).observe(document.documentElement,{subtree:true,childList:true}); } catch(e){}
  setInterval(maybe, 1500);
})();
"""

    private fun esc(s: String) = JSONObject.quote(s)

    fun loginOAuth() = "window.__RCH&&window.__RCH.loginOAuth();"
    fun fillCreds(user: String, pass: String) =
        "window.__RCH&&window.__RCH.fillCreds(${esc(user)},${esc(pass)});"
    fun selectCourse(code: String) = "window.__RCH&&window.__RCH.selectCourse(${esc(code)});"
    fun markAttendance() = "window.__RCH&&window.__RCH.markAttendance();"
    fun verify() = "window.__RCH&&window.__RCH.verify();"
    fun logout() = "window.__RCH&&window.__RCH.logout();"
}
