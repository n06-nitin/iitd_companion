# IITD Companion — AI & DevTools Perks Checklist

> [!IMPORTANT]
> **READ BEFORE EDITING ANY PERKS OR OUTREACH FILES**
> This repository houses the live mobile application source code and its dynamic web assets.
> 
> * **Primary Outreach & Partnership Tracking Dashboard:**
>   All live pipeline tracking, company contact lists, email outreach statuses, lead stages, and deal notes for AI and Developer Tools are actively tracked in Obsidian at:
>   `file:///Users/nitin/obsidian/IITD%20Companion/Perks_AI_Dev_Checklist.md`
>
> * **Strategic Playbook & Email Templates:**
>   Refer to `/Users/nitin/obsidian/IITD Companion/Perks_AI and Dev Tools.md` and `/Users/nitin/obsidian/IITD Companion/Perks_Partnerships.md` for background strategy, offer constructs, and cold mailing scripts.

---

## 1. Operating Rules for Agents & Contributors

Before proposing, contacting, or editing any company perk in IITD Companion, you **MUST** follow this workflow:

1. **Check the Obsidian Tracking Sheet First:**
   * Open `/Users/nitin/obsidian/IITD Companion/Perks_AI_Dev_Checklist.md`.
   * Verify whether the company has already been contacted, declined, or is currently in discussion.
   * Do not double-contact or change offer terms without updating the tracker.

2. **Verify Student Eligibility & Legitimacy:**
   * Every perk added must be verified for Indian university students (`@iitd.ac.in`).
   * No misleading or expired offers.
   * Paid sponsorships require a confirmed exclusive student offer / code before going live.

3. **Three-Way File Synchronization Rule:**
   When a new perk is finalized and approved to go live, all 3 files **must be updated simultaneously**:
   1. [`docs/perks.html`](file:///Users/nitin/7_IITD_Companion/docs/perks.html) *(Remote dynamic URL fetched live by mobile apps)*
   2. [`android/app/src/main/assets/perks.html`](file:///Users/nitin/7_IITD_Companion/android/app/src/main/assets/perks.html) *(Offline Android fallback)*
   3. [`ios/IITDCompanion/Resources/perks.html`](file:///Users/nitin/7_IITD_Companion/ios/IITDCompanion/Resources/perks.html) *(Offline iOS fallback)*

4. **Git Push for Instant Mobile Live-Update:**
   Once files are edited and tested:
   ```bash
   git add docs/perks.html android/app/src/main/assets/perks.html ios/IITDCompanion/Resources/perks.html
   git commit -m "Add [Company] to IITD Perks spotlight"
   git push origin main
   ```
   The mobile apps fetch `docs/perks.html` from `main` on launch; updates reflect instantly without rebuilding APKs or submitting App Store updates.

---

## 2. Quick Links

- **Obsidian AI & Dev Tracking:** [`Perks_AI_Dev_Checklist.md`](file:///Users/nitin/obsidian/IITD%20Companion/Perks_AI_Dev_Checklist.md)
- **AI Strategy & Templates:** [`Perks_AI and Dev Tools.md`](file:///Users/nitin/obsidian/IITD%20Companion/Perks_AI%20and%20Dev%20Tools.md)
- **General Partnerships:** [`Perks_Partnerships.md`](file:///Users/nitin/obsidian/IITD%20Companion/Perks_Partnerships.md)
- **Live HTML Source:** [`docs/perks.html`](file:///Users/nitin/7_IITD_Companion/docs/perks.html)
