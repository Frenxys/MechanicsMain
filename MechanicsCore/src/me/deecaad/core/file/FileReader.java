package me.deecaad.core.file;

import me.deecaad.core.utils.LogLevel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static me.deecaad.core.MechanicsCore.debug;

public class FileReader {

    private final List<PathToSerializer> pathToSerializers;
    private final Map<String, Serializer<?>> serializers;
    private final List<ValidatorData> validatorDatas;
    private final Map<String, IValidator> validators;

    public FileReader(@Nullable List<Serializer<?>> serializers, @Nullable List<IValidator> validators) {
        this.serializers = new HashMap<>();
        this.validators = new HashMap<>();
        this.pathToSerializers = new ArrayList<>();
        this.validatorDatas = new ArrayList<>();
        addSerializers(serializers);
        addValidators(validators);
    }

    /**
     * Adds new serializers for this file reader.
     * Serializers should be added before using fillAllFiles() or fillOneFile() methods.
     *
     * @param serializers the new list serializers for this file reader
     */
    public void addSerializers(List<Serializer<?>> serializers) {
        if (serializers != null && serializers.size() > 0) {
            for (Serializer<?> serializer : serializers) {
                addSerializer(serializer);
            }
        }
    }

    /**
     * Adds new serializer for this file reader.
     * Serializers should be added before using fillAllFiles() or fillOneFile() methods.
     *
     * @param serializer the new serializer for this file reader
     */
    public void addSerializer(Serializer<?> serializer) {
        if (this.serializers.containsKey(serializer.getKeyword().toLowerCase())) {
            debug.log(LogLevel.ERROR,
                    "Can't add serializer with keyword of " + serializer.getKeyword() + " because some other serializer already has same keyword.");
            return;
        }
        this.serializers.put(serializer.getKeyword().toLowerCase(), serializer);
    }

    /**
     * Adds new validators for this file reader.
     * Validators should be added before using fillAllFiles() or fillOneFile() methods.
     *
     * @param validators the new list validators for this file reader
     */
    public void addValidators(List<IValidator> validators) {
        if (validators != null && validators.size() > 0) {
            for (IValidator validator : validators) {
                addValidator(validator);
            }
        }
    }

    /**
     * Adds new validator for this file reader.
     * Validators should be added before using fillAllFiles() or fillOneFile() methods.
     *
     * @param validator the new validator for this file reader
     */
    public void addValidator(IValidator validator) {
        if (this.validators.containsKey(validator.getKeyword().toLowerCase())) {
            debug.log(LogLevel.ERROR,
                    "Can't add validator with keyword of " + validator.getKeyword() + " because some other validator already has same keyword.");
            return;
        }
        this.validators.put(validator.getKeyword().toLowerCase(), validator);
    }

    /**
     * Iterates through all .yml files inside every directory starting from the given directory.
     * It is recommended to give this method plugin's data folder directory.
     * This also takes in account given serializers.
     *
     * @param directory the directory
     * @param ignoreFiles ignored files which name starts with any given string
     * @return the map with all configurations
     */
    public Configuration fillAllFiles(File directory, @Nullable String... ignoreFiles) {
        if (directory == null || directory.listFiles() == null) {
            throw new IllegalArgumentException("The given file MUST be a directory!");
        }
        Configuration filledMap = fillAllFilesLoop(directory, ignoreFiles);

        // Only run this once
        // That's why fillAllFilesLoop method is required
        usePathToSerializersAndValidators(filledMap);

        return filledMap;
    }

    private Configuration fillAllFilesLoop(File directory, @Nullable String... ignoreFiles) {
        // A set to determine if a file should be ignored
        Set<String> fileBlacklist = ignoreFiles == null ? new HashSet<>() : Arrays.stream(ignoreFiles).collect(Collectors.toSet());

        // Dummy map to fill
        Configuration filledMap = new LinkedConfig();

        for (File directoryFile : directory.listFiles()) {

            String name = directoryFile.getName();
            if (fileBlacklist.contains(name)) continue;

            try {
                if (name.endsWith(".yml")) {
                    Configuration newFilledMap = fillOneFile(directoryFile);

                    // This occurs when the yml file is empty
                    if (newFilledMap == null) {
                        continue;
                    }

                    filledMap.add(newFilledMap);
                } else if (directoryFile.isDirectory()) {
                    filledMap.add(fillAllFilesLoop(directoryFile));
                }
            } catch (DuplicateKeyException ex) {
                debug.log(LogLevel.ERROR, "Found duplicate keys in configuration!",
                        "This occurs when you have 2 lines in configuration with the same name",
                        "This is a huge error and WILL 100% cause issues in your guns.",
                        "Duplicates Found: " + Arrays.toString(ex.getKeys()),
                        "Found in file: " + name);

                debug.log(LogLevel.DEBUG, "Duplicate Key Exception: ", ex);
            }
        }
        return filledMap;
    }

    /**
     * Fills one file into map and returns its configuration.
     * This also takes in account given serializers.
     *
     * @param file the file to read
     * @return the map with file's configurations
     */
    public Configuration fillOneFile(File file) {
        Configuration filledMap = new LinkedConfig();

        // This is used with the serializer's pathTo functionality.
        // If a serializer is found, it's path is saved here. Any
        // variable within a serializer is then "skipped"
        String startsWithDeny = null;
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        for (String key : configuration.getKeys(true)) {
            if (startsWithDeny != null) {

                // We may still want to add this key to the map. It may make
                // it easier for developers if they don't want to deal with
                // our serializers or they want to make specific variables
                // inside of serializers -- It may save a headache
                if (key.startsWith(startsWithDeny)) {
                    continue;
                }
                startsWithDeny = null;
            }
            String[] keySplit = key.split("\\.");
            if (keySplit.length > 0) {
                // Get the last "key name" of the key
                String lastKey = keySplit[keySplit.length - 1].toLowerCase();

                IValidator validator = this.validators.get(lastKey);
                if (validator != null) {
                    validatorDatas.add(new ValidatorData(validator, file, configuration, key));
                }

                // Check if this key is a serializer and handle pathTo
                Serializer<?> serializer = this.serializers.get(lastKey);
                if (serializer != null) {
                    String pathTo = serializer.useLater(configuration, key);
                    if (pathTo != null) {
                        startsWithDeny = key;
                        pathToSerializers.add(new PathToSerializer(serializer, key, pathTo));
                        continue;
                    }
                    Object valid;
                    try {
                        valid = serializer.serialize(file, configuration, key);
                    } catch (Exception e) {
                        debug.log(LogLevel.WARN, "Caught exception from serializer " + serializer.getKeyword() + "!", e);
                        continue;
                    }
                    if (valid != null) {
                        startsWithDeny = key;
                        filledMap.set(key, valid);
                        continue;
                    }
                }
            }
            Object object = configuration.get(key);
            filledMap.set(key, object);
        }
        if (filledMap.getKeys().isEmpty()) {
            return null;
        }
        return filledMap;
    }

    /**
     * Uses all path to serializers and validators.
     * This should be used AFTER normal serialization.
     *
     * @param filledMap the filled mappings
     * @return the map with used path to serializers and validators
     */
    public Configuration usePathToSerializersAndValidators(Configuration filledMap) {
        if (!pathToSerializers.isEmpty()) {
            for (PathToSerializer pathToSerializer : pathToSerializers) {
                pathToSerializer.getSerializer().tryPathTo(filledMap, pathToSerializer.getPathWhereToStore(), pathToSerializer.getPathTo());
            }
        }
        if (!validatorDatas.isEmpty()) {
            for (ValidatorData validatorData : validatorDatas) {
                validatorData.getValidator().validate(filledMap, validatorData.getFile(), validatorData.getConfigurationSection(), validatorData.getPath());
            }
        }
        return filledMap;
    }

    /**
     * Class to hold path to serializer data for later on use
     */
    public static class PathToSerializer {

        private final Serializer<?> serializer;
        private final String pathWhereToStore;
        private final String pathTo;

        public PathToSerializer(Serializer<?> serializer, String pathWhereToStore, String pathTo) {
            this.serializer = serializer;
            this.pathWhereToStore = pathWhereToStore;
            this.pathTo = pathTo;
        }

        public Serializer<?> getSerializer() {
            return serializer;
        }

        public String getPathWhereToStore() {
            return pathWhereToStore;
        }

        public String getPathTo() {
            return pathTo;
        }
    }

    /**
     * Class to help validators
     */
    public static class ValidatorData {

        private final IValidator validator;
        private final File file;
        private final ConfigurationSection configurationSection;
        private final String path;

        public ValidatorData(IValidator validator, File file, ConfigurationSection configurationSection, String path) {
            this.validator = validator;
            this.file = file;
            this.configurationSection = configurationSection;
            this.path = path;
        }

        public IValidator getValidator() {
            return validator;
        }

        public File getFile() {
            return file;
        }

        public ConfigurationSection getConfigurationSection() {
            return configurationSection;
        }

        public String getPath() {
            return path;
        }
    }
}