package com.vdx.kinodock;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** On-demand installed-app picker. Uses the launcher's glass resources; no backdrop capture or blur. */
final class AppLibraryDialog {
    /**
     * The catalogue, the labels and the icons load here, off the UI thread: each label and icon opens the app's
     * resources, and on a TCL G09 that froze Home for 60-140 ms (2 to 4 janky frames) every time the library opened.
     */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Labels kept between openings (LOADER thread only), until the app is updated. */
    private static final Map<String, Known> KNOWN = new HashMap<>();

    private static final class Known {
        final long updated;
        final String label;

        Known(long updated, String label) {
            this.updated = updated;
            this.label = label;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Icons loaded for this opening, dropped with it. */
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();
    private final Runnable dismissed;
    private boolean closed;
    /** Only the latest load fills the catalogue, and it shows the popup if an opening is waiting. */
    private final LibraryLoads loads = new LibraryLoads();
    private final Activity activity;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final Runnable reorderHome;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean selectionMode;
    /** library_ok_opens without app_library_selection: OK opens every app, Add to Home is in the hold menu. */
    private final boolean okOpens;
    private final boolean animations;
    private final boolean rtl;
    private TextView instructions;
    private TextView countView;
    private EditText searchField;
    private TextView emptyResults;
    private int totalApps;
    final PopupWindow popup;
    private final float density;
    private List<ResolveInfo> apps = new ArrayList<>();
    private String selectedPackage;
    private int selectedPosition;
    private int selectedOffset;
    private final Map<String, Long> installTimes = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private long catalogTime;
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    private Catalog catalogAdapter;
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation spring;
    private float scrollOffset;
    private boolean highlightShown;
    private final int catalogWidth;
    private List<ResolveInfo> allApps = new ArrayList<>();
    private String searchQuery = "";
    private int gridColumns = 5;

    private void stopHighlight() {
        if (spring != null) spring.cancel();
        if (catalog != null) catalog.stopScroll();
        catalog = null;
        highlightShown = false;
    }

    private void highlight(View card, boolean animate) {
        if (highlight == null || card.getParent() == null) return;
        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
        shelf.offsetDescendantRectToMyCoords(card, rect);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        lp.width = rect.width() + dp(12);
        lp.height = rect.height() + dp(12);
        highlight.setLayoutParams(lp);
        highlight.setTranslationY(rect.top - dp(6));
        float x = rect.left - dp(6) + scrollOffset;
        if (!highlightShown || !animate || !animations) {
            spring.cancel();
            highlight.setTranslationX(x - scrollOffset);
        } else spring.animateToFinalPosition(x);
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0x18FFFFFF);
        return bg;
    }

    private void resize(int width, int height) {
        popup.setWidth(width);
        popup.setHeight(height);
        if (popup.isShowing()) popup.update(width, height);
    }

    AppLibraryDialog(Activity activity, boolean animations, boolean reducedMotion, boolean selectionMode,
                     boolean okOpens, Predicate<String> onHome, Runnable changed, Runnable reorderHome,
                     Runnable dismissed) {
        this.activity = activity;
        this.animations = animations;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion;
        this.selectionMode = selectionMode;
        this.okOpens = okOpens && !selectionMode;
        this.onHome = onHome;
        this.changed = changed;
        this.reorderHome = reorderHome;
        this.dismissed = dismissed;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(36), dp(24), dp(36), dp(24));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0A0B0E, 0xFF090A0C});
        background.setStroke(dp(1), 0x1AFFFFFF);
        panel.setBackground(background);
        View decor = activity.getWindow().getDecorView();
        catalogWidth = Math.max(dp(480), decor.getWidth());
        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int height = ViewGroup.LayoutParams.MATCH_PARENT;
        popup = new PopupWindow(panel, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            dismissActions();
            stopHighlight();
            icons.clear();
            dismissed.run();
        });
    }

    void close() {
        dismissActions();
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;   // still loading: it never shows
            dismissed.run();
        }
    }

    void show() {
        load(true);
    }

    private void reveal() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.28f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 180).start();
    }

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    /** Packages with a leanback entry, from the last refresh. */
    private final java.util.Set<String> tvPackages = new java.util.HashSet<>();

    void refresh() {
        load(false);
    }

    /**
     * Reads the catalogue on LOADER, then fills it on the UI thread. reveal: from opening the library; the popup
     * appears once the latest load has filled it, even when a refresh replaced the opening's own load.
     */
    private void load(boolean reveal) {
        dismissActions();
        int load = loads.start(reveal);
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Prefer the TV entry when an app declares both categories.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!pkg.equals(self) && !unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            Map<String, String> names = new HashMap<>();
            Map<String, Long> installed = new HashMap<>();
            for (Map.Entry<String, ResolveInfo> entry : unique.entrySet()) {
                String pkg = entry.getKey();
                long updated = 0;
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    installed.put(pkg, info.firstInstallTime);
                    updated = info.lastUpdateTime;
                } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                    installed.put(pkg, 0L);
                }
                Known known = KNOWN.get(pkg);
                if (known == null || known.updated != updated) {
                    known = new Known(updated, loadLabel(pm, entry.getValue()));
                    KNOWN.put(pkg, known);
                }
                names.put(pkg, known.label);
            }
            KNOWN.keySet().retainAll(unique.keySet());
            main.post(() -> {
                if (closed || !loads.isLatest(load)) return;
                tvPackages.clear();
                tvPackages.addAll(tv);
                installTimes.clear();
                installTimes.putAll(installed);
                labels.clear();
                labels.putAll(names);
                fill(new ArrayList<>(unique.values()));
                if (loads.takeReveal()) reveal();
            });
        });
    }

    private void fill(List<ResolveInfo> catalogue) {
        allApps = catalogue;
        totalApps = allApps.size();
        Map<String, Boolean> home = new HashMap<>();
        for (ResolveInfo app : allApps) {
            String pkg = app.activityInfo.packageName;
            home.put(pkg, onHome.test(pkg));
        }
        catalogTime = System.currentTimeMillis();
        Collator collator = Collator.getInstance();
        allApps.sort((a, b) -> {
            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
            if (selectionMode) return LibraryApps.compareCatalog(home.get(pa), label(a), pa,
                    home.get(pb), label(b), pb, collator);
            int priority = LibraryApps.comparePriority(home.get(pa), installTimes.get(pa),
                    home.get(pb), installTimes.get(pb), catalogTime);
            if (priority != 0) return priority;
            int byLabel = collator.compare(label(a), label(b));
            return byLabel != 0 ? byLabel : pa.compareTo(pb);
        });
        applySearch(searchQuery, false);
        showCatalog();
    }

    private void applySearch(String query, boolean notify) {
        searchQuery = query == null ? "" : query.trim();
        String needle = searchQuery.toLowerCase(java.util.Locale.ROOT);
        List<ResolveInfo> filtered = new ArrayList<>();
        for (ResolveInfo app : allApps) {
            String name = label(app);
            if (needle.isEmpty() || name.toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || app.activityInfo.packageName.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                filtered.add(app);
            }
        }
        apps = filtered;
        if (countView != null) {
            countView.setText(searchQuery.isEmpty()
                    ? activity.getResources().getQuantityString(R.plurals.library_app_count, totalApps, totalApps)
                    : apps.size() + " / " + totalApps);
        }
        if (!apps.isEmpty()) {
            int restored = -1;
            if (selectedPackage != null) {
                for (int i = 0; i < apps.size(); i++) {
                    if (selectedPackage.equals(apps.get(i).activityInfo.packageName)) { restored = i; break; }
                }
            }
            selectedPosition = restored >= 0 ? restored : 0;
            selectedPackage = apps.get(selectedPosition).activityInfo.packageName;
        } else {
            selectedPosition = 0;
            selectedPackage = null;
        }
        if (notify && catalogAdapter != null) {
            catalogAdapter.notifyDataSetChanged();
            emptyResults.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
            if (highlight != null) highlight.setAlpha(0f);
        }
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = loadLabel(activity.getPackageManager(), app);
        labels.put(pkg, value);
        return value;
    }

    private static String loadLabel(PackageManager pm, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try { return Cards.cleanLabel(String.valueOf(app.loadLabel(pm)), pkg); }
        catch (RuntimeException unavailable) { return pkg; }
    }

    /** The app's icon from this opening's cache, or loaded on LOADER and set once it arrives. */
    private void bindIcon(ImageView icon, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        icon.setTag(pkg);
        Drawable cached = icons.get(pkg);
        icon.setImageDrawable(cached);
        if (cached != null || !iconsLoading.add(pkg)) return;
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            Drawable loaded;
            try { loaded = app.loadIcon(pm); }
            catch (RuntimeException unavailable) { loaded = null; }
            Drawable result = loaded;
            main.post(() -> {
                iconsLoading.remove(pkg);
                if (closed) return;
                Drawable shown = result != null ? result : activity.getDrawable(R.drawable.ic_apps);
                icons.put(pkg, shown);
                // The view may show another app by now; a view bound to it later takes it from the cache.
                if (pkg.equals(icon.getTag())) icon.setImageDrawable(shown);
            });
        });
    }

    private void heading(String text) {
        TextView title = text(text, 18, 0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), dp(10), dp(12), dp(8));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Reuse translated actions instead of shipping another hint in every locale. */
    private String hint(boolean home) {
        if (selectionMode) return activity.getString(android.R.string.ok) + ": "
                + activity.getString(home ? R.string.library_remove_shortcut : R.string.library_add_home);
        return activity.getString(home || okOpens ? R.string.library_hint_open : R.string.library_hint_add);
    }

    private void showCatalog() {
        stopHighlight();
        resize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panel.removeAllViews();
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(4), dp(4), dp(8));
        ImageView back = new ImageView(activity);
        back.setImageResource(R.drawable.ic_arrow_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Back to Home");
        back.setBackground(surface(0x1AFFFFFF, 16));
        back.setFocusable(true);
        back.setClickable(true);
        back.setOnClickListener(v -> close());
        back.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.12f : 1f)
                .scaleY(focused ? 1.12f : 1f).alpha(focused ? 1f : 0.78f)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : 200).start());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(20), 0, dp(20), 0);
        TextView title = text("All apps", 24, Color.WHITE);
        title.setSingleLine(true);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));
        countView = text("", 13, 0xA6FFFFFF);
        countView.setSingleLine(true);
        countView.setText(searchQuery.isEmpty()
                ? activity.getResources().getQuantityString(R.plurals.library_app_count, totalApps, totalApps)
                : apps.size() + " / " + totalApps);
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-1, -2);
        countParams.topMargin = dp(5);
        heading.addView(countView, countParams);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(56), 1f));

        TextView reorder = text("Reorder Home", 14, Color.WHITE);
        reorder.setGravity(Gravity.CENTER);
        reorder.setPadding(dp(16), 0, dp(16), 0);
        reorder.setBackground(surface(0x1AFFFFFF, 14));
        reorder.setFocusable(true);
        reorder.setClickable(true);
        reorder.setOnClickListener(v -> {
            close();
            main.post(reorderHome);
        });
        reorder.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.05f : 1f)
                .scaleY(focused ? 1.05f : 1f).alpha(focused ? 1f : 0.78f)
                .setDuration(reducedMotion ? Motion.REDUCED_MS : 200).start());
        LinearLayout.LayoutParams reorderParams = new LinearLayout.LayoutParams(-2, dp(48));
        reorderParams.setMargins(0, 0, dp(16), 0);
        header.addView(reorder, reorderParams);

        searchField = new EditText(activity);
        searchField.setSingleLine(true);
        searchField.setTextSize(16);
        searchField.setTextColor(Color.WHITE);
        searchField.setHintTextColor(0x88FFFFFF);
        searchField.setHint("Search apps");
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        searchField.setPadding(dp(18), 0, dp(18), 0);
        searchField.setBackground(surface(0x1AFFFFFF, 14));
        searchField.setTypeface(UiFont.get(activity, R.string.font_medium));
        searchField.setSelectAllOnFocus(false);
        searchField.setContentDescription("Search apps");
        searchField.setText(searchQuery);
        header.addView(searchField, new LinearLayout.LayoutParams(dp(320), dp(48)));
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                focusSelectedApp();
                return true;
            }
            return false;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applySearch(s.toString(), true);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(68)));
        instructions = text(hint(false), 13, 0xBBFFFFFF);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(76), dp(2), dp(12), dp(8));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(34)));
        panel.setFocusableInTouchMode(apps.isEmpty());
        shelf = new FrameLayout(activity);
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        gridColumns = Math.max(4, Math.min(8, (int) ((catalogWidth / density - 72) / 160)));
        list.setLayoutManager(new GridLayoutManager(activity, gridColumns));
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(dp(8), dp(12), dp(8), dp(16));
        catalogAdapter = new Catalog();
        list.setAdapter(catalogAdapter);
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        highlight = new GlassView(activity, null);
        highlight.setRadius(dp(18));
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shelf.addView(highlight, new FrameLayout.LayoutParams(dp(144), dp(180)));
        emptyResults = text(searchQuery.isEmpty() ? activity.getString(R.string.library_empty) : "No matching apps",
                18, 0xBBFFFFFF);
        emptyResults.setGravity(Gravity.CENTER);
        emptyResults.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        shelf.addView(emptyResults, new FrameLayout.LayoutParams(-1, -1));
        scrollOffset = 0;
        spring = new SpringAnimation(highlight, new FloatPropertyCompat<View>("libraryScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffset; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffset); }
        });
        if (reducedMotion) Motion.reduce(spring);
        else spring.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollOffset += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
                highlight.setTranslationY(highlight.getTranslationY() - dy);
            }
        });
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.setFocusableInTouchMode(apps.isEmpty());
        if (apps.isEmpty()) {
            panel.requestFocus();
        } else {
            int position = Math.max(0, Math.min(selectedPosition, apps.size() - 1));
            for (int i = 0; i < apps.size(); i++) {
                if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
            }
            ((GridLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, selectedOffset);
            final int target = position;
            list.post(() -> {
                if (catalog != list || !popup.isShowing()) return;
                RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
            });
        }
        if (searchField != null) searchField.clearFocus();
    }

    private void focusSelectedApp() {
        if (catalog == null || apps.isEmpty()) return;
        int position = Math.max(0, Math.min(selectedPosition, apps.size() - 1));
        catalog.scrollToPosition(position);
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    private void restoreCardFocus() {
        if (catalog == null || !popup.isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing() || actionPopup != null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void changeHome(ResolveInfo app, boolean add) {
        if (!LibraryApps.save(activity, app.activityInfo.packageName, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (selectionMode) {
            updateSelectionFocus(app, add);
            changed.run();
            fill(new ArrayList<>(allApps));
            return;
        }
        selectedOffset = 0;
        if (add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = catalog.getLayoutManager().getDecoratedTop(card)
                        - params.topMargin - catalog.getPaddingTop();
            }
            // Advance through missing shortcuts instead of following the newly checked app
            // into the Home group. Preserve its screen slot when the catalogue is rebuilt.
            int next = LibraryApps.nextFocus(selectedPosition, apps.size(),
                    index -> !onHome.test(apps.get(index).activityInfo.packageName));
            if (next >= 0) {
                selectedPackage = apps.get(next).activityInfo.packageName;
                selectedPosition = next;
            }
        }
        dismissActions();
        changed.run();
        refresh();
    }

    private void updateSelectionFocus(ResolveInfo app, boolean add) {
        int position = apps.indexOf(app);
        int next = add ? -1 : LibraryApps.nextAfterRemoval(position, apps.size());
        selectedOffset = 0;
        if (!add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = catalog.getLayoutManager().getDecoratedTop(card)
                        - params.topMargin - catalog.getPaddingTop();
            }
        }
        selectedPackage = next >= 0 ? apps.get(next).activityInfo.packageName : app.activityInfo.packageName;
    }

    private void open(ResolveInfo app) {
        close();
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        selectedPackage = app.activityInfo.packageName;
        String pkg = selectedPackage;
        if (spring != null) spring.cancel();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(menuPanel, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            restoreCardFocus();
        });
        addAction(rows, R.string.library_open, () -> open(app));
        boolean home = onHome.test(pkg);
        addAction(rows, home ? R.string.library_remove_shortcut : R.string.library_add_home,
                () -> changeHome(app, !home));
        Intent info = SystemSettings.appInfo(activity, pkg);
        if (info != null) addAction(rows, R.string.library_app_info, () -> {
            close();
            try {
                activity.startActivity(info);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
            }
        });
        int menuWidth = dp(220);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            menuWidth = Math.max(menuWidth, (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + dp(58));
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        menuWidth = Math.min(menuWidth, panel.getWidth() - dp(24));
        menu.setWidth(menuWidth);
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int[] panelLocation = new int[2];
        panel.getLocationOnScreen(panelLocation);
        Rect screen = new Rect(panelLocation[0], panelLocation[1],
                panelLocation[0] + panel.getWidth(), panelLocation[1] + panel.getHeight());
        int x = Math.max(screen.left + dp(12), Math.min(location[0] + anchor.getWidth() - dp(12), screen.right - menuWidth - dp(12)));
        int y = Math.max(screen.top + dp(12), Math.min(location[1] + dp(8), screen.bottom - menuPanel.getMeasuredHeight() - dp(12)));
        menuPanel.setAlpha(0f);
        menuPanel.setPivotY(0f);
        menuPanel.setScaleY(0.92f);
        menuPanel.setTranslationY(-dp(8));
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START, x, y);
        menuPanel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, int label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.setFocusableInTouchMode(true);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
        row.setOnKeyListener((v, key, event) -> {
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity, R.string.font_medium));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, dp(166));
            params.setMargins(dp(8), dp(6), dp(8), dp(6));
            row.setLayoutParams(params);
            row.setPadding(dp(12), dp(12), dp(12), dp(10));
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView name = text("", 14, Color.WHITE);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(40));
            nameParams.topMargin = dp(8);
            content.addView(name, nameParams);
            TextView status = text("", 11, 0xBBFFFFFF);
            status.setId(R.id.status);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            content.addView(status, new LinearLayout.LayoutParams(-1, -2));
            View check = new View(activity);
            check.setId(R.id.dot);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(2), dp(2), 0);
            row.addView(check, badge);
            return new Row(row);
        }
        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            bindIcon(holder.itemView.findViewById(R.id.icon), app);
            TextView name = holder.itemView.findViewById(R.id.title);
            name.setText(label(app));
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView status = holder.itemView.findViewById(R.id.status);
            boolean home = onHome.test(pkg);
            boolean recent = !selectionMode && LibraryApps.recent(installTimes.getOrDefault(pkg, 0L), catalogTime);
            status.setText(home ? R.string.library_on_home : recent ? R.string.library_recent : R.string.library_add_home);
            status.setVisibility(home || !okOpens ? View.VISIBLE : View.INVISIBLE);
            holder.itemView.setBackground(surface(home ? 0x1475BBA4 : 0x0CFFFFFF, 18));
            View check = holder.itemView.findViewById(R.id.dot);
            check.getLayoutParams().width = dp(home ? 18 : 8);
            check.getLayoutParams().height = dp(home ? 18 : 8);
            if (home) check.setBackgroundResource(R.drawable.library_check);
            else check.setBackground(surface(0xFF70D9A0, 8));
            check.setVisibility(home || recent ? View.VISIBLE : View.GONE);
            ImageView icon = holder.itemView.findViewById(R.id.icon);
            icon.setAlpha(holder.itemView.hasFocus() ? 1f : 0.9f);
            holder.itemView.setScaleX(holder.itemView.hasFocus() ? 1.06f : 1f);
            holder.itemView.setScaleY(holder.itemView.hasFocus() ? 1.06f : 1f);
            holder.itemView.setAlpha(holder.itemView.hasFocus() ? 1f : 0.88f);
            holder.itemView.setOnClickListener(v -> {
                boolean onHomeNow = onHome.test(pkg);
                if (LibraryApps.okOpens(selectionMode, okOpens, onHomeNow)) open(app);
                else changeHome(app, !onHomeNow);
            });
            holder.itemView.setOnLongClickListener(selectionMode ? null : v -> { showActions(app, v); return true; });
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                v.animate().cancel();
                v.setBackground(surface(focused
                        ? (home ? 0x3475BBA4 : 0x32FFFFFF)
                        : (home ? 0x1475BBA4 : 0x0CFFFFFF), 18));
                v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f)
                        .alpha(focused ? 1f : 0.88f)
                        .translationZ(focused ? dp(8) : 0f)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                icon.animate().cancel();
                icon.animate().alpha(focused ? 1f : 0.9f)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : 220).start();
                if (focused) {
                    selectedPackage = pkg;
                    instructions.setText(hint(onHome.test(pkg)));
                    int index = holder.getBindingAdapterPosition();
                    if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                }
            });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                int index = holder.getBindingAdapterPosition();
                if (event.getAction() != KeyEvent.ACTION_DOWN || index == RecyclerView.NO_POSITION) return false;
                int column = index % gridColumns;
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if ((rtl && key == KeyEvent.KEYCODE_DPAD_RIGHT)
                        || (!rtl && key == KeyEvent.KEYCODE_DPAD_LEFT)) return column == 0;
                if ((rtl && key == KeyEvent.KEYCODE_DPAD_LEFT)
                        || (!rtl && key == KeyEvent.KEYCODE_DPAD_RIGHT))
                    return column == gridColumns - 1 || index == apps.size() - 1;
                return false;
            });
        }
        @Override public int getItemCount() { return apps.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        Row(View view) { super(view); }
    }
}
