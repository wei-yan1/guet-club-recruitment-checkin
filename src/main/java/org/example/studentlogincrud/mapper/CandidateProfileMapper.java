package org.example.studentlogincrud.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.studentlogincrud.entity.CandidateProfile;

@Mapper
public interface CandidateProfileMapper extends BaseMapper<CandidateProfile> {
}
