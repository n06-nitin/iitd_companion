package com.xlonlabs.iitdcompanion

import org.json.JSONObject

/**
 * A plain username/password login form (Moodle, Moodle New, Roundcube webmail),
 * described by CSS selectors taken from each site's real markup:
 *   moodle:     form#login  #username  #password  #loginbtn  + arithmetic CAPTCHA  #valuepkg3
 *   moodlenew:  form#login  #username  #password  #loginbtn
 *   webmail:    form#login-form  #rcmloginuser  #rcmloginpwd  #rcmloginsubmit
 *
 * The injected script reports which state the page is in and fills the form
 * on request. It only presses "Log in" itself when the form has no CAPTCHA;
 * a CAPTCHA is always answered by the human.
 */
class LoginForm(
    val form: String,
    val user: String,
    val pass: String,
    val submit: String,
    val error: String,
    val captcha: String? = null,
    val remember: String? = null,
    /** Matches on pages that show a signed-out view, which should jump to [loginUrl]. */
    val loggedOut: String? = null,
    val loginUrl: String? = null,
) {
    val bootstrap: String by lazy {
        val config = JSONObject(
            mapOf(
                "form" to form, "user" to user, "pass" to pass, "submit" to submit, "error" to error,
                "captcha" to captcha, "remember" to remember, "loggedOut" to loggedOut,
            )
        )
        BOOTSTRAP.replace("__CONFIG__", config.toString())
    }

    fun fill(user: String, pass: String, submit: Boolean) =
        "window.__IC&&window.__IC.fill(${JSONObject.quote(user)},${JSONObject.quote(pass)},$submit);"

    companion object {
        val MOODLE = LoginForm(
            form = "form#login", user = "#username", pass = "#password", submit = "#loginbtn",
            error = "#loginerrormessage, .loginerrors, form#login .error",
            captcha = "#valuepkg3", remember = "#rememberusername",
            loggedOut = "body.notloggedin", loginUrl = "https://moodle.iitd.ac.in/login/index.php",
        )
        val MOODLE_NEW = LoginForm(
            form = "form#login", user = "#username", pass = "#password", submit = "#loginbtn",
            error = "#loginerrormessage, .loginerrors, .alert-danger",
            loggedOut = "body.notloggedin", loginUrl = "https://moodlenew.iitd.ac.in/login/index.php",
        )
        val WEBMAIL = LoginForm(
            form = "form#login-form", user = "#rcmloginuser", pass = "#rcmloginpwd", submit = "#rcmloginsubmit",
            error = "#messagestack .error",
        )

        private const val BOOTSTRAP = """
(function(){
  if (window.__IC_INSTALLED) { try { window.__IC.report(); } catch(e){} return; }
  window.__IC_INSTALLED = true;
  var S = __CONFIG__;

  function post(o){ try { AndroidIC.postMessage(JSON.stringify(o)); } catch(e){} }
  function q(sel){ if(!sel) return null; try { return document.querySelector(sel); } catch(e){ return null; } }
  function txt(el){ return el ? ((el.innerText||el.textContent||'')+'').trim() : ''; }
  function onLogin(){ return !!(q(S.form) && q(S.user) && q(S.pass)); }
  function detect(){
    if (onLogin()) return 'login';
    if (q(S.loggedOut)) return 'loggedout';
    return 'in';
  }
  function setVal(el,v){
    if(!el) return false;
    try { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set.call(el,v); }
    catch(e){ el.value=v; }
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
    return true;
  }

  window.__IC={
    fill:function(user,pass,submit){
      var u=q(S.user), p=q(S.pass), cap=q(S.captcha), rem=q(S.remember);
      var ok=setVal(u,user) && setVal(p,pass);
      if (rem) rem.checked=true;
      if (cap) {
        if (cap.value==='0') setVal(cap,'');
        cap.setAttribute('inputmode','numeric');
        cap.setAttribute('enterkeyhint','go');
        cap.setAttribute('autocomplete','off');
      }
      post({type:'filled', ok:ok, captcha:!!cap});
      if (ok && submit && !cap) {
        var b=q(S.submit);
        if (b) b.click(); else if (u.form) u.form.submit();
      }
    },
    report:function(){
      var page=detect();
      post({type:'state', page:page, error:page==='login'?txt(q(S.error)):''});
    }
  };

  var last='', timer=0;
  function maybe(){
    var page=detect(), sig=page+'|'+(page==='login'?txt(q(S.error)):'');
    if(sig!==last){ last=sig; window.__IC.report(); }
  }
  document.addEventListener('DOMContentLoaded', maybe);
  maybe();
  try {
    new MutationObserver(function(){ clearTimeout(timer); timer=setTimeout(maybe,250); })
      .observe(document.documentElement,{subtree:true,childList:true});
  } catch(e){}
})();
"""
    }
}
