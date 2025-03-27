package com.example.llmn.common.utils;

import com.example.llmn.common.exceptions.CustomException;
import com.example.llmn.common.exceptions.ExceptionCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public class FileUtils {

    private FileUtils() {}

    public static final String LOGS_DIRECTORY = "logs";

    public static void writeFile(MultipartFile file, Path path) {
        try {
            Files.write(path, file.getBytes());
        } catch (IOException e) {
            log.error("{}에 파일 저장 실패", path);
            throw new CustomException(ExceptionCode.SAVE_FILE_FAIL);
        }
    }

    public static void createDirectoryIfNotExist(String directoryName) {
        try {
            Path path = Paths.get(directoryName);

            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (IOException e){
            log.error("{}에 대한 디렉토리 생성 실패", directoryName);
            throw new CustomException(ExceptionCode.CREATE_DIR_FAIL);
        }
    }

    public static void validateFileExist(MultipartFile file) {
        if (file.isEmpty()) {
            throw new CustomException(ExceptionCode.NO_FILE);
        }
    }

    public static String readFileAsString(String fileName) {
        Path path = Paths.get(LOGS_DIRECTORY, fileName);
        validateFileExists(path);

        return readAllLinesAsString(path);
    }

    public static Resource getFileAsResource(String directoryName, String fileName) {
        Path path = Paths.get(directoryName, fileName);
        validateFileExists(path);

        try {
            return new UrlResource(path.toUri());
        } catch (IOException e){
            throw new CustomException(ExceptionCode.CONVERT_TO_FILE_FAIL);
        }
    }

    public static List<String> findTextFiles(String directoryName) {
        Path path = Paths.get(directoryName);

        if (isDirectoryInvalid(path)) {
            return Collections.emptyList();
        }

        return listTextFilesInDirectory(path);
    }

    public static Path getFilePath(String directoryName, MultipartFile file){
        String fileName = file.getOriginalFilename();
        return Paths.get(directoryName + File.separator + fileName);
    }
    

    private static String readAllLinesAsString(Path path) {
        try {
            List<String> lines = Files.readAllLines(path);
            return String.join("\n\n", lines);
        } catch (IOException e) {
            log.error("파일 읽기 실패: {}", path, e);
            throw new CustomException(ExceptionCode.FILE_READ_FAIL);
        }
    }

    private static boolean isDirectoryInvalid(Path directoryPath) {
        return !(Files.exists(directoryPath) && Files.isDirectory(directoryPath));
    }

    private static List<String> listTextFilesInDirectory(Path path) {
        try (Stream<Path> fileStream = Files.list(path)) {
            return fileStream
                    .filter(Files::isRegularFile)
                    .filter(FileUtils::isTextFile)
                    .map(FileUtils::getFileName)
                    .toList();
        } catch (IOException e) {
            log.error("디렉토리 {}에서 텍스트 파일 목록을 가져오는 중 오류 발생", path, e);
            return Collections.emptyList();
        }
    }

    private static boolean isTextFile(Path path) {
        return path.toString().endsWith(".txt");
    }

    private static String getFileName(Path path) {
        return path.getFileName().toString();
    }

    private static void validateFileExists(Path path) {
        if (!Files.exists(path)) {
            throw new CustomException(ExceptionCode.FILE_NOT_FOUND);
        }
    }
}
