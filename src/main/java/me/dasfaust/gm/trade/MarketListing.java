package me.dasfaust.gm.trade;

import cn.mineclay.WarApps.itemtags.ecoalternative.ItemTagEcoAlternative;
import com.google.gson.annotations.Expose;
import com.meowj.langutils.lang.LanguageHelper;
import com.meowj.langutils.lang.convert.EnumLang;
import me.dasfaust.gm.Core;
import me.dasfaust.gm.config.Config.Defaults;
import me.dasfaust.gm.menus.MarketViewer;
import me.dasfaust.gm.menus.Menus;
import me.dasfaust.gm.storage.abs.MarketObject;
import me.dasfaust.gm.storage.abs.StorageHandler;
import me.dasfaust.gm.tools.GMLogger;
import me.dasfaust.gm.tools.LocaleHandler;
import me.dasfaust.gm.trade.ListingsHelper.TransactionException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.totemcraftmc.ClayCentral.bukkit.api.ClayCentralAPI;
import org.totemcraftmc.ClayCentral.bukkit.profile.PlayerProfile;
import org.totemcraftmc.bukkitplugin.Mailbox.api.MailboxAPI;
import org.totemcraftmc.bukkitplugin.Mailbox.exchange.ExchangeFailedException;
import org.totemcraftmc.bukkitplugin.Mailbox.exchange.InternalErrorException;
import org.totemcraftmc.bukkitplugin.Mailbox.mail.Mail;
import redis.clients.johm.Attribute;
import redis.clients.johm.Model;

import java.util.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Model
public class MarketListing extends MarketObject
{
	@Expose
	@Attribute
	public UUID seller;

	@Expose
	@Attribute
	public double price;

	@Override
	public Map<Long, MarketObject> createMap()
	{
		return new LinkedHashMap<Long, MarketObject>();
	}

	@Override
	public WrappedStack onItemCreated(MarketViewer viewer, WrappedStack stack)
	{
		stack.setAmount(amount);
		if (Core.instance.config().get(Defaults.ENABLE_DEBUG))
		{
			stack.addLoreLast(Arrays.asList(new String[] {
				String.format("Object ID: %s", id),
				String.format("Item ID: %s", itemId)
			}));
		}
		stack.addLoreLast(Arrays.asList(new String[] {
				LocaleHandler.get().get("menu_listings_seller", Core.instance.storage().findPlayer(seller)),
				LocaleHandler.get().get("menu_listings_price", Core.instance.econ().format(price)),
				LocaleHandler.get().get("menu_listings_action_buy"),
				LocaleHandler.get().get("menu_action_remove")
		}));
		return stack;
	}

	@Override
	public WrappedStack onClick(MarketViewer viewer, WrappedStack stack)
	{
		GMLogger.debug("MarketListing onClick");
		GMLogger.debug("Clicks: " + viewer.timesClicked);
		Player player = viewer.player();
		if (viewer.lastClickType == ClickType.SHIFT_LEFT)
		{
			if (!(viewer.uuid.equals(seller) || player.hasPermission("globalmarket.listingsadmin")))
			{
				List<String> lore = stack.getLore();
				lore.set(lore.size() - 1, LocaleHandler.get().get("general_no_permission"));
				stack.setLore(lore);
				viewer.reset();
				return stack;
			}
			if (viewer.timesClicked < 1)
			{
				List<String> lore = stack.getLore();
				lore.set(lore.size() - 1, LocaleHandler.get().get("menu_action_remove_confirm"));
				stack.setLore(lore);
				return stack;
			}
			Core.instance.storage().removeObject(MarketListing.class, id);
			//player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_HIT, 1, 1);
//			if (Core.instance.config().get(Defaults.DISABLE_STOCK))
//			{
				player.setItemOnCursor(Core.instance.storage().get(itemId).setAmount(amount).checkNbt().bukkit());
//			}
			Core.instance.handler().rebuildAllMenus(Menus.MENU_LISTINGS);
		}
		else if (viewer.lastClickType == ClickType.LEFT)
		{
			if (viewer.uuid.equals(seller))
			{
				List<String> lore = stack.getLore();
				lore.set(lore.size() - 2, LocaleHandler.get().get("general_already_owned"));
				stack.setLore(lore);
				viewer.reset();
				return stack;
			}
			if (viewer.timesClicked < 1)
			{
				List<String> lore = stack.getLore();
				lore.set(lore.size() - 2, LocaleHandler.get().get("menu_listings_action_buy_confirm"));
				stack.setLore(lore);
				return stack;
			}

			double buyprice;
			double sellprice;

			try
			{
				double[] v = ListingsHelper.buy(this, viewer.uuid);
				buyprice = v[0];
				sellprice = v[1];
				viewer.reset();
			}
			catch(TransactionException e)
			{
				List<String> lore = stack.getLore();
				lore.set(lore.size() - 2, ChatColor.RED + e.getLocalizedMessage());
				stack.setLore(lore);
				viewer.reset();
				//player.playSound(player.getLocation(), Sound.BLOCK_WOOD_BREAK, 1, 1);
				return stack;
			}
			//player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
//			if (Core.instance.config().get(Defaults.DISABLE_STOCK))
//			{
//				player.setItemOnCursor(Core.instance.storage().get(itemId).setAmount(amount).checkNbt().bukkit());
//			}
			ItemStack tradedItem = Core.instance.storage().get(itemId).setAmount(amount).checkNbt().bukkit();
			Core.instance.getServer().getScheduler().runTaskAsynchronously(Core.instance, () -> {
				boolean finish = false;
				String errorMsg = ChatColor.RED + "出现了一些异常，导致您未完成购买，如需帮助请联系管理员";

				Mail itemMail = null;
				Mail sellPriceMail = null;
				try {
					itemMail = MailboxAPI.getInstance().getSystemAccount().createMail();
					itemMail.setReceiver(MailboxAPI.getInstance().getPlayerAccount(player));
					itemMail.setOriginalItems(Collections.singletonList(tradedItem));
					itemMail.setTitle("你在寄售行购买的物品");

					PlayerProfile sellerProfile = ClayCentralAPI.getInstance().getProfileManager().getProfileByUUID(seller).get();

					sellPriceMail = MailboxAPI.getInstance().getSystemAccount().createMail();
					sellPriceMail.setReceiver(MailboxAPI.getInstance().getPlayerAccount(sellerProfile.getName()));
					ItemStack ecoAlternativeItem = ItemTagEcoAlternative.createEcoAlternativeItem(sellprice);
					sellPriceMail.setOriginalItems(Collections.singletonList(ecoAlternativeItem));
					sellPriceMail.setTitle("你在寄售行售出了" + getItemName(tradedItem));
					itemMail.send();
					sellPriceMail.send();
					finish = true;
				} catch (ExchangeFailedException e) {
					errorMsg = ChatColor.RED + e.getErrorMessage();
				} catch (Exception e) {
					Core.instance.getLogger().log(Level.SEVERE, "error processing player purchasing item from GlobalMarket", e);
				}
				if (!finish) {
					player.sendMessage(errorMsg);
					Core.instance.econ().depositPlayer(player, buyprice);
					if (itemMail != null && itemMail.isSent()) itemMail.delete();
					if (sellPriceMail != null && sellPriceMail.isSent()) itemMail.delete();
				}
			});
		}
		return null;
	}
	
	@Override
	public void onTick(StorageHandler storage)
	{
//		if (Core.instance.config().get(Defaults.DISABLE_STOCK))
//		{
//			// TODO: there's not really much we can do here. If the player is offline, there is nowhere for the item to go
//			return;
//		}
//		int expireTime = Core.instance.config().get(Defaults.LISTINGS_EXPIRE_TIME);
//		if (expireTime > 0)
//		{
//			long diff = System.currentTimeMillis() - creationTime;
//			if (diff / (60 * 60 * 1000) > expireTime)
//			{
//				GMLogger.debug(String.format("Listing expired. ID: %s, itemId: %s", id, itemId));
//				Core.instance.storage().removeObject(MarketListing.class, id);
//				Core.instance.handler().rebuildAllMenus(Menus.MENU_LISTINGS);
//			}
//		}
	}

	public static String getItemName(ItemStack item) {
		ItemMeta meta = item.getItemMeta();
		if (meta == null || meta.getDisplayName() == null || meta.getDisplayName().isEmpty()) {
			return getMaterialName(item);
		}
		return meta.getDisplayName();
	}

	public static String getMaterialName(ItemStack item) {
		Material material = item.getType();

		String def;
		if (Bukkit.getPluginManager().isPluginEnabled("LangUtils")) {
			def = LanguageHelper.getItemDisplayName(item, EnumLang.ZH_CN.getLocale());
		} else {
			def = material.toString().replace("_", " ").toLowerCase();
			def = def.substring(0, 1).toUpperCase() + def.substring(1);
			Pattern p = Pattern.compile("[ ]");
			Matcher matcher = p.matcher(def);
			while (matcher.find()) {
				def = def.substring(0, matcher.start() + 1) + def.substring(matcher.start() + 1, matcher.start() + 2).toUpperCase() + def.substring(matcher.start() + 2);
			}
		}
		return def;
	}
}
