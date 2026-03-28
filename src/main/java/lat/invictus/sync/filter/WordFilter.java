package lat.invictus.sync.filter;

import lat.invictus.sync.InvictusSync;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WordFilter {

    private final InvictusSync plugin;
    private final Set<String> words = new HashSet<>();

    public WordFilter(InvictusSync plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        words.clear();
        // Cargar palabras del config.yml
        List<String> configWords = plugin.getConfig().getStringList("word-filter.words");
        for (String w : configWords) {
            words.add(w.toLowerCase().trim());
        }
    }

    public void addWord(String word) {
        words.add(word.toLowerCase().trim());
    }

    public void removeWord(String word) {
        words.remove(word.toLowerCase().trim());
    }

    public Set<String> getWords() {
        return words;
    }

    /**
     * Devuelve la palabra prohibida encontrada, o null si no hay ninguna.
     */
    public String findMatch(String message) {
        if (!plugin.getConfig().getBoolean("word-filter.enabled", true)) return null;
        String lower = message.toLowerCase();
        for (String word : words) {
            if (lower.contains(word)) return word;
        }
        return null;
    }
}
