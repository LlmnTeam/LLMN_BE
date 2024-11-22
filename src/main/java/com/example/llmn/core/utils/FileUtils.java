package com.example.llmn.core.utils;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
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
            log.error(path + "에 파일 저장 실패");
            throw new CustomException(ExceptionCode.SAVE_FILE_FAIL);
        }
    }

    public static String readFileAsString(String fileName) {
        Path path = Paths.get(LOGS_DIRECTORY, fileName);
        validateFileExists(path);

        return readAllLinesAsString(path);
    }

    public static Resource getFileAsResource(String directoryName, String fileName) {
        try {
            Path filePath = Paths.get(directoryName, fileName);
            validateFileExists(filePath);

            return new UrlResource(filePath.toUri());
        } catch (IOException e){
            throw new CustomException(ExceptionCode.CONVERT_TO_FILE_FAIL);
        }
    }

    public static List<String> getFileList(String directoryName) {
        Path path = Paths.get(directoryName);

        if (isDirectoryValid(path)) {
            return Collections.emptyList();
        }

        return listTextFiles(path);
    }

    public static Path getFilePath(String directoryName, MultipartFile file){
        String fileName = file.getOriginalFilename();
        return Paths.get(directoryName + File.separator + fileName);
    }

    public static BufferedWriter getBufferedWriter(String filePath, boolean append) throws IOException {
        return new BufferedWriter(new FileWriter(filePath, append));
    }

    public static void createDirIfNotExist(String directoryName) {
        Path uploadPath = Paths.get(directoryName);

        try {
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }
        } catch (IOException e){
            log.error(directoryName + "에 대한 디렉토리 생성 실패");
            throw new CustomException(ExceptionCode.CREATE_DIR_FAIL);
        }
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

    private static List<String> listTextFiles(Path filePath) {
        try (Stream<Path> fileListStream = Files.list(filePath)) {
            return fileListStream
                    .filter(Files::isRegularFile) // 일반 파일만 가져옴
                    .filter(path -> path.toString().endsWith(".txt")) // .txt 파일만 가져옴
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            log.error("로그 파일 목록을 가져오는 중 오류 발생했습니다.");
            return Collections.emptyList();
        }
    }

    private static boolean isDirectoryValid(Path directoryPath) {
        return Files.exists(directoryPath) && Files.isDirectory(directoryPath);
    }

    private static void validateFileExists(Path path) {
        if (!Files.exists(path)) {
            throw new CustomException(ExceptionCode.FILE_NOT_FOUND);
        }
    }
}
