package org.example.studentlogincrud.service;

import com.baomidou.mybatisplus.spring.service.IService;
import org.example.studentlogincrud.entity.Result;
import org.example.studentlogincrud.entity.CandidateProfile;
import org.example.studentlogincrud.entity.Student;

import java.util.List;

public interface StudentService extends IService<Student> {
    Result<Object> check(String studentNo, String ojAccount);

    Result<Student> create(Student student);

    Result<Student> update(Long id, Student student);

    Result<Student> select(Long id);

    Result<Object> delete(Long id);

    /** 按公开查询码取学生记录，不存在返回 null。 */
    Student getByPublicId(String publicId);

    CandidateProfile match(String studentNo);
}
