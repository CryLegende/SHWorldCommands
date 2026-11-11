package org.Cry_Legende.sHWorldCommands;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.error.MarkedYAMLException;

import java.io.File;
import java.io.IOException;

/**
 * Fichier YAML illisible pendant un reload : nom du fichier, ligne et problème, sans rien appliquer.
 */
final class ReloadException extends Exception {

    private final String file;
    private final int line;
    private final String problem;

    ReloadException(String file, int line, String problem) {
        super(file + (line > 0 ? " ligne " + line : "") + " : " + problem);
        this.file = file;
        this.line = line;
        this.problem = problem;
    }

    /** Lit un fichier YAML sans rien appliquer. Fichier absent : configuration vide. */
    static YamlConfiguration read(File file) throws ReloadException {
        YamlConfiguration parsed = new YamlConfiguration();
        if (!file.isFile()) {
            return parsed;
        }
        try {
            parsed.load(file);
        } catch (InvalidConfigurationException e) {
            throw of(file.getName(), e);
        } catch (IOException e) {
            throw new ReloadException(file.getName(), 0, String.valueOf(e.getMessage()));
        }
        return parsed;
    }

    /** Cherche dans la chaîne des causes l'erreur de SnakeYAML qui porte la ligne fautive. */
    private static ReloadException of(String file, Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MarkedYAMLException marked) {
                int line = marked.getProblemMark() != null ? marked.getProblemMark().getLine() + 1 : 0;
                String problem = marked.getProblem() != null ? marked.getProblem() : "syntaxe invalide";
                return new ReloadException(file, line, problem);
            }
        }
        String text = error.getMessage();
        String first = text == null ? "syntaxe invalide" : text.lines().findFirst().orElse("syntaxe invalide");
        return new ReloadException(file, 0, first);
    }

    String file() {
        return file;
    }

    String line() {
        return line > 0 ? String.valueOf(line) : "?";
    }

    String problem() {
        return problem;
    }
}
