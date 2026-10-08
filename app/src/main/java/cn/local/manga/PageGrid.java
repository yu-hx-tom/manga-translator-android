package cn.local.manga;

import android.app.Activity;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.util.function.IntConsumer;

/** Recycled thumbnail picker used by page navigation and export selection. */
final class PageGrid {
    static GridView create(
            Activity a, ComicProject project, boolean[] selected, IntConsumer choose) {
        GridView grid = new GridView(a);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(a, 88));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(a, 8));
        grid.setVerticalSpacing(Ui.dp(a, 8));
        grid.setAdapter(
                new BaseAdapter() {
                    public int getCount() {
                        return project.pages.size();
                    }

                    public Object getItem(int i) {
                        return project.pages.get(i);
                    }

                    public long getItemId(int i) {
                        return i;
                    }

                    public View getView(int i, View recycled, ViewGroup parent) {
                        LinearLayout cell =
                                recycled instanceof LinearLayout
                                        ? (LinearLayout) recycled
                                        : new LinearLayout(a);
                        ImageView image;
                        TextView label;
                        if (cell.getChildCount() == 0) {
                            cell.setOrientation(LinearLayout.VERTICAL);
                            cell.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
                            image = new ImageView(a);
                            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            cell.addView(image, new LinearLayout.LayoutParams(-1, Ui.dp(a, 108)));
                            label = Ui.text(a, "", 12, Ui.INK);
                            label.setGravity(Gravity.CENTER);
                            cell.addView(label, new LinearLayout.LayoutParams(-1, Ui.dp(a, 36)));
                        } else {
                            image = (ImageView) cell.getChildAt(0);
                            label = (TextView) cell.getChildAt(1);
                        }
                        ComicProject.Page page = project.pages.get(i);
                        File file =
                                page.editable()
                                        ? new File(project.draftDir(page), "rendered.png")
                                        : project.imageFile(page);
                        if (file != null && !file.isFile() && page.editable())
                            file = new File(project.draftDir(page), PageDraft.SOURCE);
                        ProjectCover.bind(image, file);
                        boolean checked = selected != null && i < selected.length && selected[i];
                        cell.setBackground(
                                Ui.round(a, checked ? Ui.ACCENT_SOFT : Ui.SURFACE_SOFT, 12));
                        label.setText(
                                label.getContext()
                                        .getString(R.string.page_grid_message_13, (i + 1)));
                        label.setCompoundDrawablesRelative(null, null, null, null);
                        if (checked) Icons.setIcon(label, R.drawable.ic_check, Ui.ACCENT, 14);
                        else if (page.reviewed)
                            Icons.setIcon(label, R.drawable.ic_check_circle, Ui.SUCCESS, 14);
                        cell.setContentDescription(
                                "第 "
                                        + (i + 1)
                                        + " 页"
                                        + (checked ? "，已选中" : "")
                                        + (page.reviewed ? "，已校对" : ""));
                        return cell;
                    }
                });
        grid.setOnItemClickListener(
                (parent, view, index, id) -> {
                    choose.accept(index);
                    ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
                });
        return grid;
    }

    static void jump(Activity a, ComicProject project, int current, IntConsumer choose) {
        Ui.Sheet sheet = Ui.sheet(a, "跳转到页");
        boolean[] selected = new boolean[project.pages.size()];
        if (current >= 0 && current < selected.length) selected[current] = true;
        GridView grid =
                create(
                        a,
                        project,
                        selected,
                        index -> {
                            sheet.dismiss();
                            choose.accept(index);
                        });
        sheet.body.addView(grid, new LinearLayout.LayoutParams(-1, Ui.dp(a, 420)));
        grid.setSelection(Math.max(0, current));
        sheet.show();
    }
}
