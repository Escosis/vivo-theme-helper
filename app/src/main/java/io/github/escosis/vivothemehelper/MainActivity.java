package io.github.escosis.vivothemehelper;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Standalone release page with no runtime dependency on the Xposed API. */
public final class MainActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.launcher_scroll);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.launcher_background));
        padForSystemBars(scroll);
        LinearLayout content = column();
        int gutter = dp(Math.max(16, (getResources().getConfiguration().screenWidthDp - 664) / 2));
        content.setPadding(gutter, dp(24), gutter, dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        addPill(content, ModuleInfo.RELEASE);
        TextView title = readingView(34);
        title.setText(R.string.app_name);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setAccessibilityHeading(true);
        content.addView(title);

        List<DocRenderer.Block> blocks = readRelease();
        int first = !blocks.isEmpty() && blocks.get(0).heading == 1 ? 1 : 0;
        while (first < blocks.size() && blocks.get(first).heading == 0) {
            addBlock(content, blocks.get(first++));
        }
        addHostActions(content);
        if (blocks.isEmpty()) {
            TextView missing = readingView(16);
            missing.setText(R.string.launcher_doc_unavailable);
            addCard(content, false).addView(missing);
        }
        LinearLayout section = content;
        for (int i = first; i < blocks.size(); i++) {
            DocRenderer.Block block = blocks.get(i);
            if (block.heading == 2) section = addCard(content, block.important);
            addBlock(section, block);
        }
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout addCard(LinearLayout parent, boolean important) {
        LinearLayout card = column();
        card.setBackground(rounded(getColor(important ? R.color.launcher_required_surface : R.color.launcher_surface), 24));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        position.topMargin = dp(14);
        parent.addView(card, position);
        if (important) addPill(card, getString(R.string.launcher_required));
        return card;
    }

    private void addPill(LinearLayout parent, String label) {
        TextView pill = readingView(13);
        pill.setText(label);
        pill.setTypeface(pill.getTypeface(), Typeface.BOLD);
        pill.setTextColor(getColor(R.color.launcher_on_tint));
        pill.setBackground(rounded(getColor(R.color.launcher_tint), 20));
        pill.setPadding(dp(10), dp(3), dp(10), dp(3));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        position.bottomMargin = dp(7);
        parent.addView(pill, position);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private List<DocRenderer.Block> readRelease() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("RELEASE.md"), StandardCharsets.UTF_8))) {
            return DocRenderer.render(reader);
        } catch (IOException error) {
            return Collections.emptyList();
        }
    }

    private void addBlock(LinearLayout content, DocRenderer.Block block) {
        TextView view = readingView(block.heading == 0 ? 16 : 21);
        view.setPadding(0, block.heading == 0 ? dp(8) : 0, 0, 0);
        if (block.heading != 0) {
            view.setTypeface(view.getTypeface(), Typeface.BOLD);
            view.setAccessibilityHeading(true);
        }
        SpannableStringBuilder text = new SpannableStringBuilder(
                Html.fromHtml(block.html, Html.FROM_HTML_MODE_COMPACT));
        if (block.marker != null) {
            String prefix = block.marker + "\u2002";
            text.insert(0, prefix);
            int indent = (int) Math.ceil(view.getPaint().measureText(prefix));
            text.setSpan(new LeadingMarginSpan.Standard(0, indent), 0, text.length(),
                    Spanned.SPAN_PARAGRAPH);
        }
        int codeColor = getColor(R.color.launcher_code);
        for (TypefaceSpan span : text.getSpans(0, text.length(), TypefaceSpan.class)) {
            if ("monospace".equals(span.getFamily())) {
                text.setSpan(new BackgroundColorSpan(codeColor), text.getSpanStart(span), text.getSpanEnd(span),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        boolean hasLink = false;
        for (URLSpan span : text.getSpans(0, text.length(), URLSpan.class)) {
            int start = text.getSpanStart(span), end = text.getSpanEnd(span);
            String url = DocRenderer.httpsUrl(span.getURL());
            text.removeSpan(span);
            if (url == null) continue;
            text.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) { openBrowser(url); }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            hasLink = true;
        }
        view.setText(text);
        if (hasLink) view.setMovementMethod(LinkMovementMethod.getInstance());
        content.addView(view);
    }

    private void addHostActions(LinearLayout content) {
        LinearLayout card = addCard(content, false);
        LinearLayout actions = new LinearLayout(this);
        Configuration config = getResources().getConfiguration();
        boolean stacked = config.screenWidthDp < 380 || config.fontScale > 1.2f;
        actions.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.addView(actions);
        int width = stacked ? MATCH_PARENT : 0;
        float weight = stacked ? 0f : 1f;

        Button open = actionButton(R.string.launcher_open_theme, true);
        open.setOnClickListener(view -> openTheme());
        actions.addView(open, new LinearLayout.LayoutParams(width, WRAP_CONTENT, weight));
        Button check = actionButton(R.string.launcher_check_host, false);
        check.setContentDescription(getString(R.string.launcher_check_host_description));
        LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(width, WRAP_CONTENT, weight);
        if (stacked) next.topMargin = dp(8);
        else next.setMarginStart(dp(8));
        actions.addView(check, next);

        TextView status = readingView(14);
        status.setTextColor(getColor(R.color.launcher_muted));
        status.setPadding(0, dp(10), 0, 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        card.addView(status);
        check.setOnClickListener(view -> {
            int count = ModuleInfo.DEX_NAMES.length;
            check.setEnabled(false);
            check.setAlpha(0.65f);
            status.setText(getString(R.string.launcher_checking_host, count));
            status.setVisibility(View.VISIBLE);
            new Thread(() -> {
                String result;
                try {
                    String mismatch = Compatibility.verify(MainActivity.this, false);
                    result = mismatch.isEmpty() ? getString(R.string.launcher_host_matched, count) : mismatch;
                } catch (Throwable error) {
                    result = getString(R.string.launcher_check_failed, error.getClass().getSimpleName(),
                            error.getMessage() != null ? error.getMessage() : "");
                }
                final String answer = result;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        status.setText(answer);
                        check.setEnabled(true);
                        check.setAlpha(1f);
                    }
                });
            }, "VivoTheme24-Verify").start();
        });

        Button repository = actionButton(R.string.launcher_open_repository, false);
        repository.setOnClickListener(view -> openBrowser("https://github.com/Escosis/vivo-theme-helper"));
        LinearLayout.LayoutParams repositoryPosition = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        repositoryPosition.topMargin = dp(8);
        card.addView(repository, repositoryPosition);
    }

    private Button actionButton(int label, boolean primary) {
        Button button = new Button(this);
        int background = getColor(primary ? R.color.launcher_accent : R.color.launcher_tint);
        int foreground = getColor(primary ? R.color.launcher_on_accent : R.color.launcher_on_tint);
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26000000 | (foreground & 0xffffff)),
                rounded(background, 24), null));
        button.setStateListAnimator(null);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        return button;
    }

    private void openTheme() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(ModuleInfo.TARGET_PACKAGE);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Report an unavailable launcher instead of opening anything else.
        }
        Toast.makeText(this, R.string.launcher_host_unavailable, Toast.LENGTH_SHORT).show();
    }

    private void openBrowser(String url) {
        // Resolve unrestricted HTTPS handlers, not apps deep-linked to the document URL.
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager manager = getPackageManager();
        int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER;
        ResolveInfo browser = manager.resolveActivity(probe, flags);
        if (!isBrowser(browser)) {
            browser = null;
            for (ResolveInfo candidate : manager.queryIntentActivities(probe, flags)) {
                if (isBrowser(candidate)) {
                    browser = candidate;
                    break;
                }
            }
        }
        if (browser != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setClassName(browser.activityInfo.packageName, browser.activityInfo.name);
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // The browser may have been disabled between resolution and launch.
            }
        }
        Toast.makeText(this, R.string.launcher_no_browser, Toast.LENGTH_SHORT).show();
    }

    private static boolean isBrowser(ResolveInfo info) {
        if (info == null || info.activityInfo == null || !info.activityInfo.exported) return false;
        IntentFilter filter = info.filter;
        return filter != null && filter.countDataAuthorities() == 0
                && filter.countDataPaths() == 0 && filter.countDataSchemeSpecificParts() == 0;
    }

    @SuppressWarnings("deprecation")
    private void padForSystemBars(ScrollView scroll) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                DisplayCutout cutout = insets.getDisplayCutout();
                view.setPadding(
                        Math.max(insets.getSystemWindowInsetLeft(), cutout == null ? 0 : cutout.getSafeInsetLeft()),
                        Math.max(insets.getSystemWindowInsetTop(), cutout == null ? 0 : cutout.getSafeInsetTop()),
                        Math.max(insets.getSystemWindowInsetRight(), cutout == null ? 0 : cutout.getSafeInsetRight()),
                        Math.max(insets.getSystemWindowInsetBottom(), cutout == null ? 0 : cutout.getSafeInsetBottom()));
            }
            return insets;
        });
    }

    private TextView readingView(int sp) {
        TextView view = new TextView(this);
        view.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        view.setTextColor(getColor(R.color.launcher_text));
        view.setLinkTextColor(getColor(R.color.launcher_accent));
        view.setTextSize(sp);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
