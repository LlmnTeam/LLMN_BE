package com.example.llmn.core.utils;

import com.example.llmn.core.errors.CustomException;
import com.example.llmn.core.errors.ExceptionCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public class FileUtils {

    public static final String LOGS_DIRECTORY = "logs";

    public static String readFileAsString(String fileName) {
        Path path = Paths.get(LOGS_DIRECTORY, fileName);

        // 1st 파일이 존재하는지 확인
        if (!Files.exists(path)) {
            throw new CustomException(ExceptionCode.FILE_NOT_FOUND);
        }

        // 2nd 파일 내용을 읽어서, 하나의 문자열로 변환 (각 줄을 \n\n으로 구분)
        try {
            List<String> lines = Files.readAllLines(path);
            return String.join("\n\n", lines);
        } catch (IOException e) {
            throw new CustomException(ExceptionCode.FILE_READ_FAIL);
        }
    }

    public static Resource getFileAsResource(String directoryName, String fileName) {
        try {
            Path filePath = Paths.get(directoryName, fileName);

            // 파일이 존재하는지 확인
            if (!Files.exists(filePath)) {
                throw new CustomException(ExceptionCode.FILE_NOT_FOUND);
            }

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
}
