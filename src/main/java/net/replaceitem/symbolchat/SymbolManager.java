package net.replaceitem.symbolchat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFinder;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.replaceitem.symbolchat.config.ClothConfigProvider;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static net.replaceitem.symbolchat.SymbolChat.NAMESPACE;

public class SymbolManager implements SimpleSynchronousResourceReloadListener {
    
    public static final Identifier IDENTIFIER = new Identifier(NAMESPACE,"symbols");
    public static final Identifier ALL_IDENTIFIER = new Identifier(NAMESPACE, "all");
    public static final ResourceFinder SYMBOLS_FINDER = new ResourceFinder("symbols", ".txt");
    public static final ResourceFinder SYMBOL_TABS_FINDER = ResourceFinder.json("symbol_tabs");

    private List<SymbolTab> tabs = List.of();
    private final HashMap<Identifier, SymbolList> listCache = new HashMap<>();
    private final SymbolList.Mutable favoritesList = new SymbolList.Mutable(new Identifier(NAMESPACE, "favorites"));
    private final SymbolList.Mutable customKaomojisList = new SymbolList.Mutable(new Identifier(NAMESPACE, "custom_kaomojis"));
    
    @Override
    public Identifier getFabricId() {
        return IDENTIFIER;
    }

    @Override
    public void reload(ResourceManager manager) {
        this.listCache.clear();
        this.addList(favoritesList);
        this.addList(customKaomojisList);
        tabs = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> identifierListEntry : SYMBOL_TABS_FINDER.findResources(manager).entrySet()) {
            Identifier identifier = SYMBOL_TABS_FINDER.toResourceId(identifierListEntry.getKey());
            Resource tabResource = identifierListEntry.getValue();
            try(BufferedReader symbolTabReader = tabResource.getReader()) {
                SymbolTab symbolTab = readTab(manager, symbolTabReader, identifier);
                tabs.add(symbolTab);
            } catch (IOException | JsonParseException e) {
                SymbolChat.LOGGER.error("Could not load symbol tab " + identifier, e);
                throw new RuntimeException(e);
            }
        }
        tabs.sort(SymbolTab::compareTo);
        tabs = Collections.unmodifiableList(tabs);
    }

    public boolean isFavorite(String symbol) {
        return favoritesList.contains(symbol);
    }

    public List<SymbolTab> getTabs() {
        return tabs;
    }
    
    public Optional<SymbolTab> getTab(Identifier identifier) {
        return tabs.stream().filter(tab -> tab.getId().equals(identifier)).findFirst();
    }
    
    public Stream<String> getFavoriteSymbols() {
        return favoritesList.stream();
    }

    @NotNull
    private SymbolTab readTab(ResourceManager manager, BufferedReader symbolTabReader, Identifier identifier) throws IOException {
        JsonObject object = JsonHelper.deserialize(symbolTabReader);
        String icon = JsonHelper.getString(object, "icon");
        int order = JsonHelper.getInt(object, "order");
        SymbolTab.Type type = SymbolTab.Type.getOrDefault(JsonHelper.getString(object, "type", null), SymbolTab.Type.SYMBOLS);
        boolean searchBar = JsonHelper.getBoolean(object, "search_bar", false);
        JsonArray symbolFiles = JsonHelper.getArray(object, "symbols", new JsonArray(0));
        List<SymbolList> symbols = readSymbolLists(manager, symbolFiles);
        return new SymbolTab(identifier, icon, order, type, searchBar, symbols);
    }

    @NotNull
    private List<SymbolList> readSymbolLists(ResourceManager manager, JsonArray symbolFiles) {
        List<SymbolList> symbols = new ArrayList<>();
        for (JsonElement symbolFile : symbolFiles) {
            if(symbolFile instanceof JsonPrimitive primitive) {
                symbols.add(readSymbolList(manager, new Identifier(primitive.getAsString()), SymbolList.SplitType.CODEPOINT));
                continue;
            }
            if(!(symbolFile instanceof JsonObject object)) continue;
            SymbolList.SplitType type = SymbolList.SplitType.getOrDefault(JsonHelper.getString(object, "split", null), SymbolList.SplitType.CODEPOINT);
            Identifier identifier = new Identifier(JsonHelper.getString(object, "symbols"));
            symbols.add(readSymbolList(manager, identifier, type));
        }
        return symbols;
    }
    
    @NotNull
    private SymbolList readSymbolList(ResourceManager manager, Identifier identifier, SymbolList.SplitType type) {
        if(listCache.containsKey(identifier)) return listCache.get(identifier);
        try(BufferedReader symbolsReader = manager.openAsReader(SYMBOLS_FINDER.toResourcePath(identifier))) {
            SymbolList symbolList = new SymbolList(identifier, type.split(symbolsReader));
            this.addList(symbolList);
            return symbolList;
        } catch (IOException | JsonParseException e) {
            SymbolChat.LOGGER.error("Could not load symbols " + identifier, e);
            throw new RuntimeException(e);
        }
    }
    
    private void addList(SymbolList symbolList) {
        this.listCache.put(symbolList.getId(), symbolList);
    }

    public void onConfigReload(ClothConfigProvider config) {
        favoritesList.clear();
        config.getFavoriteSymbols().codePoints().mapToObj(Character::toString).forEach(favoritesList::addSymbol);
        customKaomojisList.clear();
        config.getCustomKaomojis().forEach(customKaomojisList::addSymbol);
    }

    public boolean isOnlyFavorites(SymbolTab tab) {
        return tab.getSymbols().size() == 1 && tab.getSymbols().get(0) == favoritesList;
    }
}
