# CanopyChat University Flier

Printable flier for university bulletin boards to recruit environmentally conscious users to Founding Members.

**Target:** Students who care about climate, privacy, and meaningful tech.

**QR Code:** Points to `https://canopychat.app/founding.html` (custom domain `canopychat.app` → GitHub Pages)

### Files
- `flier.html` — main poster, 8.5x11" with bleed, ready to print from browser (uses brand colors, tear-off tabs)
- `flier.pdf` — print-ready PDF generated via Chromium headless
- `qr-code.png` — QR for `canopychat.app/founding.html` (error correction H, brand bark on white)
- `app-icon.png` — CanopyChat icon

### How to print
1. Open `flier.html` in Chrome/Safari
2. Print → Letter, Margins: None, Background graphics: ON
3. Or just print `flier.pdf` directly — already has background colors

### Posting tips
- Best spots: environmental studies, CS, library, co-op boards, coffee shops near campus
- Tear-off tabs at bottom have URL for takeaway
- If board doesn't allow tear-offs, cut that dashed line off — poster works without it (just trim)

### Copy (normie-friendly, no $10 up front)
- Headline: "Your AI shouldn't cost the planet."
- Sub: "Most AI lives in warehouse-size data centers that burn power and guzzle water. CanopyChat lives on your phone — just your phone."
- Points: Just your phone / No water wasted / Climate-contributing / Private by default
- CTA band: "Become a Founding Member — Early access + 3 months Premium in beta"

Amount ($10) is intentionally not on flier — revealed only at bottom of founding funnel after education. This matches the current funnel strategy.

### Regenerate QR
```python
python3 -m pip install qrcode[pil] --break-system-packages
python3 -c "import qrcode; qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_H, box_size=20, border=4); qr.add_data('https://canopychat.app/founding.html'); qr.make(fit=True); qr.make_image(fill_color='#211812', back_color='#ffffff').save('qr-code.png')"
```
