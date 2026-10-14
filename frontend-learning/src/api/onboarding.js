import request from '../utils/request'

/** 获取当前用户的 onboarding 状态(返回 needsOnboarding 等字段) */
export const getOnboardingMe = () => request({ url: '/onboarding/me', method: 'get' })

/** 完成 onboarding(提交 goal/level/preferredSubjectId) */
export const finishOnboarding = (data) => request({ url: '/onboarding/finish', method: 'post', data })

/** 跳过 onboarding */
export const skipOnboarding = () => request({ url: '/onboarding/skip', method: 'post' })

/** 重置 onboarding(让用户重做 3 步) */
export const resetOnboarding = () => request({ url: '/onboarding/reset', method: 'post' })

/** 获取推荐路径(用于 Dashboard "接下来学什么") */
export const getRecommend = () => request({ url: '/onboarding/recommend', method: 'get' })
