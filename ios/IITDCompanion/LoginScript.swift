import Foundation

/// A plain username/password login form (Moodle, Moodle New, Roundcube webmail),
/// described by CSS selectors taken from each site's real markup:
///   moodle:     form#login  #username  #password  #loginbtn  + arithmetic CAPTCHA  #valuepkg3
///   moodlenew:  form#login  #username  #password  #loginbtn
///   webmail:    form#login-form  #rcmloginuser  #rcmloginpwd  #rcmloginsubmit
///
/// The injected script reports which state the page is in and fills the form
/// on request. It only presses "Log in" itself when the form has no CAPTCHA;
/// a CAPTCHA is always answered by the human.
struct LoginForm {
    let form: String
    let user: String
    let pass: String
    let submit: String
    let error: String
    var captcha: String? = nil
    var remember: String? = nil
    /// Matches on pages that show a signed-out view, which should jump to `loginUrl`.
    var loggedOut: String? = nil
    var loginUrl: URL? = nil

    var bootstrap: String {
        let config: [String: Any] = [
            "form": form, "user": user, "pass": pass, "submit": submit, "error": error,
            "captcha": captcha ?? NSNull(), "remember": remember ?? NSNull(), "loggedOut": loggedOut ?? NSNull(),
        ]
        let json = (try? JSONSerialization.data(withJSONObject: config, options: [.sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return Self.script.replacingOccurrences(of: "__CONFIG__", with: json)
    }

    func fill(user: String, pass: String, submit: Bool) -> String {
        "window.__IC&&window.__IC.fill(\(jsQuote(user)),\(jsQuote(pass)),\(submit));"
    }

    static let moodle = LoginForm(
        form: "form#login", user: "#username", pass: "#password", submit: "#loginbtn",
        error: "#loginerrormessage, .loginerrors, form#login .error",
        captcha: "#valuepkg3", remember: "#rememberusername",
        loggedOut: "body.notloggedin", loginUrl: URL(string: "https://moodle.iitd.ac.in/login/index.php")
    )
    static let moodleNew = LoginForm(
        form: "form#login", user: "#username", pass: "#password", submit: "#loginbtn",
        error: "#loginerrormessage, .loginerrors, .alert-danger",
        loggedOut: "body.notloggedin", loginUrl: URL(string: "https://moodlenew.iitd.ac.in/login/index.php")
    )
    static let webmail = LoginForm(
        form: "form#login-form", user: "#rcmloginuser", pass: "#rcmloginpwd", submit: "#rcmloginsubmit",
        error: "#messagestack .error"
    )

    private static let script = #"""
(function(){
  if (window.__IC_INSTALLED) { try { window.__IC.report(); } catch(e){} return; }
  window.__IC_INSTALLED = true;
  var S = __CONFIG__;

  function post(o){ try { window.webkit.messageHandlers.ic.postMessage(JSON.stringify(o)); } catch(e){} }
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
"""#
}

/// A JavaScript string literal for `s`, safely quoted.
func jsQuote(_ s: String) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: s, options: [.fragmentsAllowed]),
          let quoted = String(data: data, encoding: .utf8) else { return "\"\"" }
    return quoted
}
