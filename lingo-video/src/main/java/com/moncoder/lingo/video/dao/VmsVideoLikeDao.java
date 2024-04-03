package com.moncoder.lingo.video.dao;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.moncoder.lingo.video.domain.vo.VideoLikeVO;

/**
 * @author lenovo
 * @version 1.0
 * @description TODO 自定义赞过的视频映射
 * @date 2024/4/3 19:40
 */
public interface VmsVideoLikeDao {

    /**
     *
     * @param page
     * @param userId
     * @param titleKeyWord
     * @return
     */
    IPage<VideoLikeVO> selectPageByUserId(Page<VideoLikeVO> page, Integer userId, String titleKeyWord);
}
