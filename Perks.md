# IITD Companion — IITD Perks

> *"So many perks that IITD students are offered are lost, and most of us never come to know about the opportunities we could have unlocked."*

The **IITD Perks** section is a dynamic student hub within IITD Companion designed to help IIT Delhi students discover and claim all free educational benefits, pro software licenses, cloud credits, and verified student discounts available through their `@iitd.ac.in` credentials.

---

## 1. How It Works (Fully Remote & Instant Updates)

* **Editable Source File:** [`docs/perks.html`](file:///Users/nitin/7_IITD_Companion/docs/perks.html)
* **How to Update Content or Add Promotions:**
  1. Open `docs/perks.html` in your editor.
  2. Add, edit, or remove perks, promotional banners, deals, or step instructions.
  3. Commit and push:
     ```bash
     git add docs/perks.html
     git commit -m "Update student perks and campus deals"
     git push origin main
     ```
  4. **Instant Update:** The mobile app fetches `docs/perks.html` directly from GitHub on every launch or reload. **No Play Store release or APK rebuild required!**
* **Offline Fallback:** A baseline copy is bundled in the app (`android/app/src/main/assets/perks.html`) so it paints instantly even on zero-connectivity or airplane mode.

### Should `docs/perks.html` be in `.gitignore`?
* **No!** If it is in `.gitignore`, Git will ignore it and will never push your edits to GitHub. For the mobile app to fetch your new sections, banners, and deals, the file must be tracked by Git and pushed to GitHub.

---

## 2. Design Principles

1. **Clean Badges, No Monetary Clutter:**
   * Badges only: `[Free Pro + Copilot]`, `[Full Pro IDE Suite]`, `[1 TB Cloud + Office]`.
   * **No** `$1,000+/yr Value` or monetary estimates next to the badges.
2. **Minimal Text & High Signal:**
   * Section headers: Clean title + count badge only (no redundant subtitles).
   * Crisp 1-line descriptions.
   * Concise 3-6 word verification steps.
3. **Left-Right Scrollable Image Walkthroughs:**
   * Every perk card features a horizontal carousel (`LazyRow` / CSS scroll) with 3–4 step preview cards.
4. **No "Claim Offer" Buttons:**
   * Replaced with a clean portal URL pill, a **Copy Link** button, and an **Open Portal** icon that launches in the user's default browser.
5. **Entry Point (Phase 1):**
   * Accessible via the top-right **⋮ (More)** menu > **"IITD Perks"**, keeping the bottom navigation dock 100% focused on the 4 core daily portals (Webmail, Moodle New, Moodle, RollCall).
