import LinkedInJobsSource from './linkedinJobs.js';
import LinkedInPostsSource from './linkedinPosts.js';
import WuzzufSource from './wuzzuf.js';
import TanqeebSource from './tanqeeb.js';
import NaukrigulfSource from './naukrigulf.js';
import RecruiteraSource from './recruitera.js';
import WorkableSource from './workable.js';
import VertexSource from './vertex.js';
import AtsSource from './ats.js';
import AiDataSource from './aiData.js';
import XPostsSource from './xPosts.js';
import XSessionSource from './xSession.js';
import DrJobSource from './drjob.js';
import RemoteFeedsSource from './remoteFeeds.js';
import JobgetherSource from './jobgether.js';
import SponsorBoardsSource from './sponsorBoards.js';
import HnHiringSource from './hnHiring.js';
import JobApisSource from './jobApis.js';
import JobrightSource from './jobright.js';
import RelocateMeSource from './relocateMe.js';
import { KhamsatSource, MostaqlSource } from './freelanceBoards.js';
import LinkedInWorldwideSource from './linkedinWorldwide.js';
import GlassdoorSource from './glassdoor.js';
import CareerSitesSource from './careerSites.js';

/**
 * Source registry. A source is any object with:
 *   key         string, stored in items.source
 *   isEnabled() boolean
 *   fetch(opts) Promise<item[]>
 *
 * The pipeline never names a concrete source, so adding one means writing a
 * class with that shape and appending it here. Filtering, storage, dedup,
 * retry and message formatting apply to every source unchanged.
 */
export function buildSources() {
  return [
    new LinkedInJobsSource(),
    new LinkedInPostsSource(),
    new WuzzufSource(),
    new TanqeebSource(),
    new NaukrigulfSource(),
    new RecruiteraSource(),
    new WorkableSource(),
    new VertexSource(),
    new AtsSource(),
    new AiDataSource(),
    new XPostsSource(),
    new XSessionSource(),
    // Ported from the Android app.
    new DrJobSource(),
    new RemoteFeedsSource(),
    new JobgetherSource(),
    new SponsorBoardsSource(),
    new HnHiringSource(),
    new JobApisSource(),
    new JobrightSource(),
    new RelocateMeSource(),
    new MostaqlSource(),
    new KhamsatSource(),
    new CareerSitesSource(),
    new LinkedInWorldwideSource(),
    new GlassdoorSource(),
  ];
}

export default buildSources;
