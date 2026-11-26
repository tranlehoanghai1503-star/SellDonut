package com.hai.selldonutsmp.modules;

import com.hai.selldonutsmp.util.Money;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Het do an -> /ah steak, chon stack 64 steak re nhat, bam mua, bam xac nhan.
 * SellAxe goi request() khi tay phu trong va khong con do an, va goi tick() moi tick.
 */
public class FoodBuyer {
    private enum State { IDLE, WAIT_AH, WAIT_ITEMS, WAIT_CONFIRM, WAIT_DONE }

    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int SCAN_LIMIT = 45; // chi quet 45 o tren (hang duoi la nut dieu khien)

    private final Setting<Boolean> enabled;
    private final Setting<String> command;
    private final Setting<Integer> amount;
    private final Setting<Double> maxPrice;
    private final Setting<String> priceRegex;
    private final Setting<Integer> retrySeconds;
    private final Setting<Boolean> debug;

    private State state = State.IDLE;
    private int timer = 0;
    private int cooldown = 0;
    private int beforeCount = 0;
    private int listingSyncId = -1;
    private int listingSlot = -1;
    private double listingPrice = 0;
    private String lastRegex = null;
    private Pattern lastPattern = null;

    public FoodBuyer(SettingGroup g) {
        enabled = g.add(new BoolSetting.Builder()
            .name("tu-mua-steak-khi-het-do-an")
            .description("Khi tay phu trong va khong con thuc an trong tui do thi tu /ah, mua stack steak re nhat.")
            .defaultValue(true)
            .build()
        );
        command = g.add(new StringSetting.Builder()
            .name("lenh-tim-steak")
            .description("Lenh mo cho dau gia tim steak.")
            .defaultValue("/ah steak")
            .build()
        );
        amount = g.add(new IntSetting.Builder()
            .name("so-steak-moi-stack")
            .description("Chi mua phan dang ban dung so luong steak nay (mot stack day = 64).")
            .defaultValue(64)
            .min(1)
            .sliderMax(64)
            .build()
        );
        maxPrice = g.add(new DoubleSetting.Builder()
            .name("gia-toi-da")
            .description("Khong mua neu stack re nhat dat hon muc nay (0 = khong gioi han).")
            .defaultValue(1000000)
            .min(0)
            .sliderMax(10000000)
            .build()
        );
        priceRegex = g.add(new StringSetting.Builder()
            .name("regex-doc-gia")
            .description("Regex doc gia trong mo ta (lore) cua mon do. Nhom so 1 phai la so tien.")
            .defaultValue("(?i)\\$\\s*([\\d,]+(?:\\.\\d+)?[kmbt]?)(?![a-z])")
            .build()
        );
        retrySeconds = g.add(new IntSetting.Builder()
            .name("thu-lai-sau-giay")
            .description("Neu mua that bai thi doi bao nhieu giay roi moi thu lai.")
            .defaultValue(120)
            .min(10)
            .sliderMax(600)
            .build()
        );
        debug = g.add(new BoolSetting.Builder()
            .name("go-loi-mua-do-an")
            .description("In ra cac buoc mua steak (de chinh khi mua sai).")
            .defaultValue(false)
            .build()
        );
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public void reset() {
        state = State.IDLE;
    }

    /** Yeu cau mua steak (bo qua neu dang ban, dang cho, hoac dang mo giao dien khac). */
    public void request() {
        if (!enabled.get() || state != State.IDLE || cooldown > 0) return;
        if (mc.player == null || mc.currentScreen != null) return;

        AutoSellModule sell = Modules.get().get(AutoSellModule.class);
        if (sell != null && sell.isBusy()) return;
        AutoPayModule pay = Modules.get().get(AutoPayModule.class);
        if (pay != null && pay.isBusy()) return;

        beforeCount = countSteak();
        String cmd = command.get().trim();
        if (!cmd.startsWith("/")) cmd = "/" + cmd;
        ChatUtils.sendPlayerMsg(cmd);
        ChatUtils.info("Het do an, dang tim steak tren /ah...");

        state = State.WAIT_AH;
        timer = 100;
    }

    public void tick() {
        if (cooldown > 0) cooldown--;
        if (mc.player == null || mc.interactionManager == null) return;

        switch (state) {
            case IDLE -> {}
            case WAIT_AH -> {
                if (containerScreen() != null) {
                    state = State.WAIT_ITEMS;
                    timer = 15; // cho server do danh sach vao giao dien
                } else if (--timer <= 0) {
                    fail("Khong mo duoc giao dien /ah.");
                }
            }
            case WAIT_ITEMS -> {
                if (--timer <= 0) scanAndBuy();
            }
            case WAIT_CONFIRM -> waitConfirm();
            case WAIT_DONE -> {
                if (--timer <= 0) done();
            }
        }
    }

    // ---------- Cac buoc ----------

    private void scanAndBuy() {
        GenericContainerScreen screen = containerScreen();
        if (screen == null) {
            fail("Giao dien /ah da dong.");
            return;
        }
        GenericContainerScreenHandler h = screen.getScreenHandler();
        int limit = Math.min(SCAN_LIMIT, h.getRows() * 9);

        int bestSlot = -1;
        double best = Double.MAX_VALUE;
        int candidates = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (!st.isOf(Items.COOKED_BEEF) || st.getCount() != amount.get()) continue;

            double price = readPrice(st);
            if (price < 0) continue;
            candidates++;
            if (price < best) {
                best = price;
                bestSlot = i;
            }
        }

        if (debug.get()) ChatUtils.info("Quet /ah: " + candidates + " phan steak " + amount.get() + " co gia doc duoc.");

        if (bestSlot < 0) {
            fail("Khong thay stack " + amount.get() + " steak nao (co gia doc duoc) o trang dau cua /ah.");
            return;
        }
        if (maxPrice.get() > 0 && best > maxPrice.get()) {
            fail("Stack steak re nhat co gia " + Money.format(best) + ", cao hon gia-toi-da " + Money.format(maxPrice.get()) + ".");
            return;
        }

        ChatUtils.info("Mua stack " + amount.get() + " steak gia " + Money.format(best) + " (re nhat).");
        click(h, bestSlot);
        listingSyncId = h.syncId;
        listingSlot = bestSlot;
        listingPrice = best;
        state = State.WAIT_CONFIRM;
        timer = 60;
    }

    private void waitConfirm() {
        GenericContainerScreen screen = containerScreen();
        if (screen == null) { // giao dien dong lai -> co the da mua luon
            state = State.WAIT_DONE;
            timer = 10;
            return;
        }

        GenericContainerScreenHandler h = screen.getScreenHandler();
        boolean sameWindow = h.syncId == listingSyncId;
        boolean listingChanged = sameWindow && !isListing(h.getSlot(listingSlot).getStack());

        if (!sameWindow || listingChanged) { // da hien bang xac nhan
            int btn = findConfirm(h);
            if (btn >= 0) {
                if (debug.get()) ChatUtils.info("Bam nut xac nhan o so " + (btn + 1) + ".");
                click(h, btn);
                state = State.WAIT_DONE;
                timer = 20;
                return;
            }
            if (listingChanged) { // o danh sach da bien mat, khong co bang xac nhan -> coi nhu da mua
                state = State.WAIT_DONE;
                timer = 10;
                return;
            }
        }

        if (--timer <= 0) fail("Bam vao steak nhung khong thay nut xac nhan mua.");
    }

    private void done() {
        closeScreen();
        int after = countSteak();
        if (after > beforeCount) {
            ChatUtils.info("Da mua steak (" + Money.format(listingPrice) + "), se dua sang tay phu.");
            cooldown = 100;
        } else {
            ChatUtils.warning("Khong thay steak moi trong tui do, co the mua that bai.");
            cooldown = retrySeconds.get() * 20;
        }
        state = State.IDLE;
    }

    private void fail(String reason) {
        ChatUtils.warning(reason);
        closeScreen();
        state = State.IDLE;
        cooldown = retrySeconds.get() * 20;
    }

    // ---------- Tien ich ----------

    private GenericContainerScreen containerScreen() {
        return mc.currentScreen instanceof GenericContainerScreen s ? s : null;
    }

    private void closeScreen() {
        if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen) mc.player.closeHandledScreen();
    }

    private void click(GenericContainerScreenHandler h, int slot) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
    }

    private boolean isListing(ItemStack st) {
        return st.isOf(Items.COOKED_BEEF) && st.getCount() == amount.get();
    }

    // Nut xac nhan: kinh xanh lime / bloc xanh, hoac ten co chu confirm
    private int findConfirm(GenericContainerScreenHandler h) {
        int top = h.getRows() * 9;
        for (int i = 0; i < top; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) continue;
            if (st.isOf(Items.LIME_STAINED_GLASS_PANE) || st.isOf(Items.LIME_CONCRETE) || st.isOf(Items.EMERALD_BLOCK)) return i;
            if (st.getName().getString().toLowerCase().contains("confirm")) return i;
        }
        return -1;
    }

    // Doc gia tu lore; uu tien dong co chu price/gia, neu khong thi dong dau tien co dau $
    private double readPrice(ItemStack st) {
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return -1;

        double fallback = -1;
        for (Text line : lore.lines()) {
            String t = line.getString();
            Matcher m = pricePattern().matcher(t);
            if (!m.find()) continue;
            double v;
            try {
                v = Money.parse(m.group(1));
            } catch (Exception e) {
                continue;
            }
            String low = t.toLowerCase();
            if (low.contains("price") || low.contains("gia")) return v;
            if (fallback < 0) fallback = v;
        }
        return fallback;
    }

    private Pattern pricePattern() {
        String r = priceRegex.get();
        if (!r.equals(lastRegex)) {
            try {
                lastPattern = Pattern.compile(r);
            } catch (Exception e) {
                ChatUtils.warning("regex-doc-gia khong hop le.");
                lastPattern = Pattern.compile("a^");
            }
            lastRegex = r;
        }
        return lastPattern;
    }

    private int countSteak() {
        int n = 0;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = mc.player.playerScreenHandler.getSlot(i).getStack();
            if (s.isOf(Items.COOKED_BEEF)) n += s.getCount();
        }
        return n;
    }
}
