package com.moncoder.lingo.video.service.impl;

import com.moncoder.lingo.common.constant.VideoConstant;
import com.moncoder.lingo.common.service.IRedisService;
import com.moncoder.lingo.entity.VmsHomeTrendingVideo;
import com.moncoder.lingo.mapper.VmsHomeTrendingVideoMapper;
import com.moncoder.lingo.video.domain.vo.VideoViewVO;
import com.moncoder.lingo.video.service.IVmsHomeTrendingVideoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 最热视频表 服务实现类
 * </p>
 *
 * @author moncoder
 * @since 2024-04-14 13:18:20
 */
@Service
public class VmsHomeTrendingVideoServiceImpl extends ServiceImpl<VmsHomeTrendingVideoMapper, VmsHomeTrendingVideo>
        implements IVmsHomeTrendingVideoService {

    @Autowired
    private IRedisService redisService;

    @Transactional(rollbackFor = Exception.class)
    @Override
    public List<VideoViewVO> getTrendingVideos() {
        // 1.从缓存中获取
        Map<Object, Object> map = redisService.hGetAll(VideoConstant.VMS_VIDEO_HOME_TRENDING_KEY);
        List<VmsHomeTrendingVideo> videos = new ArrayList<>();
        for (Object key : map.keySet()) {
            Object value = map.get(key);
            if (value instanceof VmsHomeTrendingVideo) {
                videos.add((VmsHomeTrendingVideo) value);
            }
        }
        // 2.缓存中获取不到，从数据库中获取
        if (videos.size() == 0) {
            videos = lambdaQuery().eq(VmsHomeTrendingVideo::getStatus, (byte) 1).list();
            // 加载到缓存
            HashMap<String, Object> newMap = new HashMap<>(videos.size());
            videos.forEach(video -> newMap.put(video.getId().toString(), video));
            redisService.hSetAll(VideoConstant.VMS_VIDEO_HOME_TRENDING_KEY, newMap);
            redisService.expire(VideoConstant.VMS_VIDEO_HOME_TRENDING_KEY,
                    VideoConstant.VMS_VIDEO_HOME_EXPIRE);
        }
        return videos.stream().map(video -> {
            VideoViewVO videoViewVO = new VideoViewVO();
            BeanUtils.copyProperties(video, videoViewVO);
            return videoViewVO;
        }).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean disableTrendingVideos() {
        // 1.取消所有推荐视频
        lambdaUpdate().eq(VmsHomeTrendingVideo::getStatus,(byte)1)
                .set(VmsHomeTrendingVideo::getStatus,(byte)0)
                .update();

        // 2.删除缓存
        redisService.delete(VideoConstant.VMS_VIDEO_HOME_TRENDING_KEY);
        return true;
    }
}
