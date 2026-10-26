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
 * on request. Moodle's CAPTCHA is a sum written out in the page text
 * ("Please add 49 + 83 =", "Please subtract 97 - 46 =",
 * "Please enter first value 64 , 80 =", "Please enter second value 42 , 27 ="),
 * so the script answers it and presses "Log in". A question it doesn't
 * recognise is left to the human.
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
  // the question is the bare text right before the input (a <label> in demo mode)
  function question(cap){
    var t='';
    for (var n=cap.previousSibling; n && n.nodeType===3; n=n.previousSibling) t=n.nodeValue+t;
    if (!/\d/.test(t) && cap.id) t=txt(q('label[for="'+cap.id+'"]'));
    return t.replace(/\s+/g,' ');
  }
  function solve(cap){
    var m=/please (add|subtract|enter first value|enter second value) (-?\d+) ?[-+,] ?(-?\d+) ?=/i.exec(question(cap));
    if (!m) return null;
    var a=parseInt(m[2],10), b=parseInt(m[3],10), op=m[1].toLowerCase();
    return String(op==='add' ? a+b : op==='subtract' ? a-b : op==='enter first value' ? a : b);
  }

  window.__IC={
    fill:function(user,pass,submit){
      var u=q(S.user), p=q(S.pass), cap=q(S.captcha), rem=q(S.remember);
      var ok=setVal(u,user) && setVal(p,pass);
      var answer=cap ? solve(cap) : null;
      if (rem) rem.checked=true;
      if (cap) {
        if (answer!==null) setVal(cap,answer);
        else if (cap.value==='0') setVal(cap,'');
        cap.setAttribute('inputmode','numeric');
        cap.setAttribute('enterkeyhint','go');
        cap.setAttribute('autocomplete','off');
      }
      var human=!!cap && answer===null;
      post({type:'filled', ok:ok, captcha:human});
      if (ok && submit && !human) {
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
